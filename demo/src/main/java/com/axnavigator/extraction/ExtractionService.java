package com.axnavigator.extraction;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.axnavigator.ax.AxCalcService;
import com.axnavigator.ax.calc.AxCalculator;
import com.axnavigator.common.*;
import com.axnavigator.company.*;
import com.axnavigator.doc.*;
import com.axnavigator.extraction.ExtractionDtos.*;
import com.axnavigator.task.*;

@Service
public class ExtractionService {
    private final ExtractionDraftRepository drafts;
    private final TaskService tasks;
    private final CompanyRepository companies;
    private final DepartmentRepository departments;
    private final BusinessProcessRepository processes;
    private final ProcessMetricRepository metrics;
    private final ItSystemRepository systems;
    private final ProcessSystemRepository links;
    private final DocumentRepository documents;
    private final AxCalcService calc;
    private final JsonSupport json;
    private final ZoneId zone;

    public ExtractionService(ExtractionDraftRepository drafts, TaskService tasks, CompanyRepository companies,
            DepartmentRepository departments, BusinessProcessRepository processes, ProcessMetricRepository metrics,
            ItSystemRepository systems, ProcessSystemRepository links, DocumentRepository documents, AxCalcService calc,
            JsonSupport json, AxProperties properties) {
        this.drafts = drafts; this.tasks = tasks; this.companies = companies; this.departments = departments;
        this.processes = processes; this.metrics = metrics; this.systems = systems; this.links = links;
        this.documents = documents; this.calc = calc; this.json = json; this.zone = ZoneId.of(properties.timeZone());
    }

    /** Agent가 추출 결과를 저장한다. 같은 작업의 재시도는 draft를 덮어쓴다. */
    @Transactional
    public Saved save(SaveRequest request) {
        AiTask task = tasks.lock(request.taskId());
        if (task.getStatus() != TaskStatus.RUNNING) throw ApiException.invalidState("실행 중인 작업만 추출 결과를 저장할 수 있습니다.");
        if (departments.existsByCompanyId(task.getCompanyId())) throw ApiException.invalidState("이미 기업 데이터가 확정되었습니다.");
        validate(request.payload());
        Set<Long> indexed = new HashSet<>(documents.findByCompanyIdAndStatusOrderByDocumentId(task.getCompanyId(), DocumentStatus.INDEXED)
            .stream().map(Document::getDocumentId).toList());
        if (!indexed.containsAll(request.documentIds())) throw ApiException.validation("기업의 색인 완료 문서만 사용할 수 있습니다.", Map.of("validIds", indexed.stream().sorted().toList()));
        ExtractionDraft draft = drafts.findByTaskId(task.getTaskId()).orElseGet(ExtractionDraft::new);
        if (draft.getStatus() == DraftStatus.CONFIRMED) throw ApiException.invalidState("이미 확정된 추출 결과입니다.");
        draft.setCompanyId(task.getCompanyId()); draft.setTaskId(task.getTaskId()); draft.setStatus(DraftStatus.DRAFT);
        draft.setDocumentIds(json.write(request.documentIds())); draft.setPayload(json.write(request.payload()));
        return new Saved(drafts.saveAndFlush(draft).getDraftId());
    }

    @Transactional(readOnly = true)
    public Draft draft(String taskId, Long userId, Long companyId) {
        tasks.owned(tasks.find(taskId), userId, companyId);
        return toDraft(find(taskId));
    }

    /** 사용자가 수정한 추출 결과를 검증해 기업 데이터로 저장하고 Agent를 재개한다. */
    @Transactional
    public void confirm(String taskId, Long userId, Long companyId, Payload payload) {
        AiTask task = tasks.owned(tasks.lock(taskId), userId, companyId);
        if (task.getStatus() != TaskStatus.WAITING_CONFIRMATION) throw ApiException.invalidState("확정 대기 중인 작업만 확정할 수 있습니다.");
        ExtractionDraft draft = find(taskId);
        validate(payload);
        Company company = companies.lockById(task.getCompanyId()).orElseThrow();
        // 회사당 한 번만 확정한다. 재확정은 기존 로드맵이 업무를 참조하므로 별도로 다룬다.
        if (departments.existsByCompanyId(company.getCompanyId())) throw ApiException.invalidState("이미 기업 데이터가 확정되었습니다.");
        persist(company, payload);
        draft.setPayload(json.write(payload)); draft.setStatus(DraftStatus.CONFIRMED); draft.setConfirmedAt(LocalDateTime.now(zone));
        tasks.confirmed(task);
    }

    private ExtractionDraft find(String taskId) {
        return drafts.findByTaskId(taskId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "추출 결과를 찾을 수 없습니다."));
    }

    private Draft toDraft(ExtractionDraft draft) {
        return new Draft(draft.getDraftId(), draft.getStatus(), json.read(draft.getDocumentIds()), json.read(draft.getPayload()));
    }

    /** key 중복과 참조를 검사한다. 형식 검증은 Bean Validation이 먼저 한다. */
    static void validate(Payload payload) {
        Set<String> departmentKeys = unique(payload.departments().stream().map(ExtractionDtos.Department::key).toList(), "부서");
        Set<String> processKeys = unique(payload.processes().stream().map(ExtractionDtos.Process::key).toList(), "업무");
        unique(payload.systems().stream().map(SystemInfo::key).toList(), "시스템");
        for (ExtractionDtos.Process process : payload.processes())
            if (!departmentKeys.contains(process.departmentKey()))
                throw ApiException.validation("업무 " + process.key() + "의 부서 " + process.departmentKey() + "가 없습니다.", Map.of("validKeys", sorted(departmentKeys)));
        Set<String> measured = new HashSet<>();
        for (Metric metric : payload.metrics()) {
            if (!processKeys.contains(metric.processKey()))
                throw ApiException.validation("지표의 업무 " + metric.processKey() + "가 없습니다.", Map.of("validKeys", sorted(processKeys)));
            if (!measured.add(metric.processKey())) throw ApiException.validation("업무 " + metric.processKey() + "의 지표가 중복되었습니다.");
        }
        for (SystemInfo system : payload.systems())
            for (String key : system.processKeys())
                if (!processKeys.contains(key))
                    throw ApiException.validation("시스템 " + system.key() + "의 업무 " + key + "가 없습니다.", Map.of("validKeys", sorted(processKeys)));
    }

    private static Set<String> unique(List<String> keys, String label) {
        Set<String> seen = new LinkedHashSet<>();
        for (String key : keys) if (!seen.add(key)) throw ApiException.validation(label + " key " + key + "가 중복되었습니다.");
        return seen;
    }

    private static List<String> sorted(Set<String> keys) { return keys.stream().sorted().toList(); }

    private void persist(Company company, Payload payload) {
        if (payload.company() != null) {
            if (payload.company().industry() != null) company.setIndustry(payload.company().industry());
            if (payload.company().employeeCount() != null) company.setEmployeeCount(payload.company().employeeCount());
        }
        Map<String, Long> departmentIds = new HashMap<>();
        for (ExtractionDtos.Department item : payload.departments()) {
            com.axnavigator.company.Department department = new com.axnavigator.company.Department();
            department.setCompanyId(company.getCompanyId()); department.setDepartmentName(item.name());
            departmentIds.put(item.key(), departments.save(department).getDepartmentId());
        }
        Map<String, Long> processIds = new HashMap<>();
        for (ExtractionDtos.Process item : payload.processes()) {
            BusinessProcess process = new BusinessProcess();
            process.setDepartmentId(departmentIds.get(item.departmentKey())); process.setProcessName(item.name()); process.setPurpose(item.purpose());
            process.setProcessSteps(item.steps() == null ? null : json.write(item.steps())); process.setStandardizationLevel(item.standardizationLevel());
            process.setRequiresApproval(item.requiresApproval()); process.setExceptionRules(item.exceptionRules()); process.setWorkType(item.workType());
            processIds.put(item.key(), processes.save(process).getProcessId());
        }
        // 월평균 1건을 계산 기준일의 최근 3개 완료 월에 같은 값으로 저장한다.
        List<YearMonth> months = AxCalculator.recentCompletedMonths(calc.asOf());
        for (Metric item : payload.metrics()) {
            for (YearMonth month : months) {
                ProcessMetric metric = new ProcessMetric();
                metric.setProcessId(processIds.get(item.processKey())); metric.setPeriodStart(month.atDay(1)); metric.setPeriodEnd(month.atEndOfMonth());
                metric.setProcessingCount(item.monthlyCount()); metric.setAvgProcessingMinutes(item.avgMinutes()); metric.setErrorRate(item.errorRate());
                metric.setHourlyLaborCostKrw(item.hourlyLaborCostKrw()); metric.setDataSource(item.dataSource());
                metrics.save(metric);
            }
        }
        for (SystemInfo item : payload.systems()) {
            ItSystem system = new ItSystem();
            system.setCompanyId(company.getCompanyId()); system.setSystemName(item.name()); system.setSystemType(item.type());
            system.setApiAvailable(item.apiAvailable()); system.setDataAccessLevel(item.dataAccessLevel()); system.setCloudBased(item.cloudBased());
            Long systemId = systems.save(system).getSystemId();
            for (String key : new LinkedHashSet<>(item.processKeys())) {
                ProcessSystem link = new ProcessSystem();
                link.setId(new ProcessSystemId(processIds.get(key), systemId));
                links.save(link);
            }
        }
        links.flush();
    }
}
