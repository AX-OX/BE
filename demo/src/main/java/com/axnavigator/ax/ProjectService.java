package com.axnavigator.ax;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.axnavigator.ax.StorageDtos.*;
import com.axnavigator.common.*;
import com.axnavigator.company.DepartmentRepository;

@Service
public class ProjectService {
    private final AxProjectRepository projects;
    private final RoadmapItemRepository items;
    private final AxRoadmapRepository roadmaps;
    private final DepartmentRepository departments;
    public ProjectService(AxProjectRepository projects, RoadmapItemRepository items, AxRoadmapRepository roadmaps, DepartmentRepository departments) {
        this.projects = projects; this.items = items; this.roadmaps = roadmaps; this.departments = departments;
    }
    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        RoadmapItem item = items.lockByItemId(request.itemId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,"로드맵 항목을 찾을 수 없습니다."));
        AxRoadmap roadmap = roadmaps.findById(item.getRoadmapId()).orElseThrow();
        if (roadmap.getApprovalStatus() != ApprovalStatus.APPROVED) throw ApiException.invalidState("승인된 로드맵의 항목만 프로젝트로 등록할 수 있습니다.");
        var existing = projects.findByItemId(item.getItemId());
        if (existing.isPresent()) return new ProjectResponse(existing.get().getProjectId());
        var department = departments.findById(request.ownerDepartmentId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,"담당 부서를 찾을 수 없습니다."));
        if (!department.getCompanyId().equals(roadmap.getCompanyId())) throw ApiException.validation("로드맵과 같은 기업의 부서만 담당할 수 있습니다.");
        AxProject project = new AxProject(); project.setItemId(item.getItemId()); project.setOwnerDepartmentId(request.ownerDepartmentId());
        project.setProjectName(request.projectName()); project.setProjectStatus(ProjectStatus.REGISTERED);
        return new ProjectResponse(projects.saveAndFlush(project).getProjectId());
    }
    @Transactional
    public StatusResponse status(Long projectId, StatusRequest request) {
        AxProject project = projects.lockByProjectId(projectId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,"프로젝트를 찾을 수 없습니다."));
        ProjectStatus current = project.getProjectStatus(), next = request.status();
        boolean valid = current == next || current == ProjectStatus.ON_HOLD || next == ProjectStatus.ON_HOLD
            || current == ProjectStatus.REGISTERED && next == ProjectStatus.IN_PROGRESS
            || current == ProjectStatus.IN_PROGRESS && next == ProjectStatus.COMPLETED;
        if (!valid) throw ApiException.invalidState("허용되지 않은 프로젝트 상태 변경입니다: " + current + " → " + next);
        project.setProjectStatus(next);
        return new StatusResponse(projectId, next);
    }
}
