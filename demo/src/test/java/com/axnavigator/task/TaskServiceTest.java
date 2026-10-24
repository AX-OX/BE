package com.axnavigator.task;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.axnavigator.ax.AxAssessmentRepository;
import com.axnavigator.common.ApiException;
import com.axnavigator.common.AxProperties;
import com.axnavigator.common.ErrorCode;
import com.axnavigator.common.JsonSupport;
import com.axnavigator.company.DepartmentRepository;
import com.axnavigator.doc.Document;
import com.axnavigator.doc.DocumentRepository;
import com.axnavigator.doc.DocumentStatus;
import com.axnavigator.extraction.ExtractionDraftRepository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskServiceTest {
    private final AiTaskRepository tasks = mock(AiTaskRepository.class);
    private final DepartmentRepository departments = mock(DepartmentRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private TaskService service() {
        AxProperties properties = mock(AxProperties.class);
        when(properties.timeZone()).thenReturn("Asia/Seoul");
        return new TaskService(tasks, departments, documents, mock(ExtractionDraftRepository.class),
            mock(AxAssessmentRepository.class), new JsonSupport(), events, properties, mock(com.axnavigator.recovery.CommandQueue.class));
    }

    @Test
    void unconfirmedCompanyWithoutIndexedDocumentsDoesNotCreateOrDispatchTask() {
        when(documents.findByCompanyIdAndStatusOrderByDocumentId(7L, DocumentStatus.INDEXED)).thenReturn(List.of());

        assertThatThrownBy(() -> service().create(701L, 7L, new TaskDtos.CreateRequest("기업 데이터 분석")))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_STATE);
                assertThat(ex.getMessage()).contains("색인이 완료된 후");
            });

        verifyNoInteractions(tasks, events);
    }

    @Test
    void indexedDocumentsAllowExtractionTaskToStart() {
        Document document = new Document();
        document.setDocumentId(410L);
        when(documents.findByCompanyIdAndStatusOrderByDocumentId(7L, DocumentStatus.INDEXED)).thenReturn(List.of(document));

        TaskDtos.Created result = service().create(701L, 7L, new TaskDtos.CreateRequest("기업 데이터 분석"));

        ArgumentCaptor<TaskSignals.Run> run = ArgumentCaptor.forClass(TaskSignals.Run.class);
        verify(events).publishEvent(run.capture());
        assertThat(run.getValue().taskId()).isEqualTo(result.taskId());
        assertThat(run.getValue().companyDataConfirmed()).isFalse();
        assertThat(run.getValue().documentIds()).containsExactly(410L);
        verify(tasks).saveAndFlush(any(AiTask.class));
    }

    @Test
    void confirmedCompanyCanAnalyzeWithoutUploadedDocuments() {
        when(departments.existsByCompanyId(7L)).thenReturn(true);

        service().create(701L, 7L, new TaskDtos.CreateRequest("AX 과제 추천"));

        ArgumentCaptor<TaskSignals.Run> run = ArgumentCaptor.forClass(TaskSignals.Run.class);
        verify(events).publishEvent(run.capture());
        assertThat(run.getValue().companyDataConfirmed()).isTrue();
        assertThat(run.getValue().documentIds()).isEmpty();
        verifyNoInteractions(documents);
    }
}
