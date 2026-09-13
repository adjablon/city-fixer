package com.example.city_fix.report;

import com.example.city_fix.IntegrationTest;
import com.example.city_fix.user.Role;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaffReportControllerTest extends IntegrationTest {

    @Test
    void staffMap_showsReportsFiledByOtherUsers() throws Exception {
        MockHttpSession resident = registerResident("staff-map-owner@example.com");
        submitReport(resident, "Report only staff should aggregate");
        Long reportId = findByDescription("Report only staff should aggregate").getId();

        MockHttpSession staff = authenticateAs("staff-map@example.com", Role.STAFF);

        mockMvc.perform(get("/staff/reports").session(staff))
            .andExpect(status().isOk())
            .andExpect(model().attribute("reportsJson", containsString("\"id\":" + reportId)));
    }

    @Test
    void staffMap_isReachableByAdmin() throws Exception {
        MockHttpSession admin = authenticateAs("staff-map-admin@example.com", Role.ADMIN);

        mockMvc.perform(get("/staff/reports").session(admin))
            .andExpect(status().isOk());
    }

    @Test
    void staffRoutes_forbiddenToResident_evenForTheirOwnReport() throws Exception {
        MockHttpSession resident = registerResident("staff-denied@example.com");
        submitReport(resident, "My own report, denied on the staff surface");
        Long reportId = findByDescription("My own report, denied on the staff surface").getId();

        mockMvc.perform(get("/staff/reports").session(resident))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/staff/reports/" + reportId).session(resident))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/staff/reports/" + reportId + "/photo").session(resident))
            .andExpect(status().isForbidden());
    }

    @Test
    void staffDetail_showsTheReporterAndTheReport() throws Exception {
        MockHttpSession resident = registerResident("detail-owner@example.com");
        submitReport(resident, "Report visible in full to staff");
        Long reportId = findByDescription("Report visible in full to staff").getId();

        MockHttpSession staff = authenticateAs("detail-staff@example.com", Role.STAFF);

        mockMvc.perform(get("/staff/reports/" + reportId).session(staff))
            .andExpect(status().isOk())
            .andExpect(model().attribute("reporterEmail", "detail-owner@example.com"))
            .andExpect(model().attribute("hasPhoto", true))
            .andExpect(content().string(containsString("Report visible in full to staff")));
    }

    @Test
    void staffPhoto_isServedToStaffForAnotherUsersReport() throws Exception {
        MockHttpSession resident = registerResident("photo-owner@example.com");
        submitReport(resident, "Report whose photo staff must be able to fetch");
        Long reportId = findByDescription("Report whose photo staff must be able to fetch").getId();

        MockHttpSession staff = authenticateAs("photo-staff@example.com", Role.STAFF);

        mockMvc.perform(get("/staff/reports/" + reportId + "/photo").session(staff))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_JPEG))
            .andExpect(content().bytes(jpegBytes()));
    }

    @Test
    void staffDetail_returns404ForAMissingReport() throws Exception {
        MockHttpSession staff = authenticateAs("missing-staff@example.com", Role.STAFF);

        mockMvc.perform(get("/staff/reports/999999").session(staff))
            .andExpect(status().isNotFound());
    }

    @Test
    void statusChange_persistsAndTheReportingResidentSeesIt() throws Exception {
        MockHttpSession resident = registerResident("loop-owner@example.com");
        submitReport(resident, "Report that completes the triage loop");
        Long reportId = findByDescription("Report that completes the triage loop").getId();

        MockHttpSession staff = authenticateAs("loop-staff@example.com", Role.STAFF);
        mockMvc.perform(post("/staff/reports/" + reportId + "/status")
                .param("status", "IN_PROGRESS")
                .session(staff)
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/staff/reports/" + reportId));

        Report persisted = reportRepository.findById(reportId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.IN_PROGRESS);
        assertThat(persisted.getStatusUpdatedAt()).isNotNull();

        // The second half of the PRD's primary success criterion: the resident sees it.
        mockMvc.perform(get("/reports/" + reportId).session(resident))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("In progress")))
            .andExpect(content().string(containsString("Status updated")));
    }

    @Test
    void statusChange_toTheCurrentStatusLeavesTheTimestampUntouched() throws Exception {
        MockHttpSession resident = registerResident("noop-owner@example.com");
        submitReport(resident, "Report submitted twice with the same status");
        Long reportId = findByDescription("Report submitted twice with the same status").getId();

        MockHttpSession staff = authenticateAs("noop-staff@example.com", Role.STAFF);
        changeStatus(staff, reportId, "RESOLVED");
        Instant firstChange = reportRepository.findById(reportId).orElseThrow().getStatusUpdatedAt();

        changeStatus(staff, reportId, "RESOLVED");

        Report persisted = reportRepository.findById(reportId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(persisted.getStatusUpdatedAt()).isEqualTo(firstChange);
    }

    @Test
    void statusChange_acceptsAnyTransitionIncludingBackwards() throws Exception {
        MockHttpSession resident = registerResident("transitions-owner@example.com");
        submitReport(resident, "Report walked through every status");
        Long reportId = findByDescription("Report walked through every status").getId();

        MockHttpSession staff = authenticateAs("transitions-staff@example.com", Role.STAFF);
        changeStatus(staff, reportId, "RESOLVED");
        changeStatus(staff, reportId, "NEW");

        assertThat(reportRepository.findById(reportId).orElseThrow().getStatus())
            .isEqualTo(ReportStatus.NEW);
    }

    @Test
    void statusChange_isForbiddenToResident_evenForTheirOwnReport() throws Exception {
        MockHttpSession resident = registerResident("status-denied-resident@example.com");
        submitReport(resident, "Report whose status its reporter must not change");
        Long reportId = findByDescription("Report whose status its reporter must not change").getId();

        // The CSRF token is load-bearing. Without it the CSRF filter rejects the request before
        // authorization runs, the assertion still sees 403, and this test would keep passing with
        // the /staff/** role rule deleted.
        mockMvc.perform(post("/staff/reports/" + reportId + "/status")
                .param("status", "RESOLVED")
                .session(resident)
                .with(csrf()))
            .andExpect(status().isForbidden());

        Report persisted = reportRepository.findById(reportId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.NEW);
        assertThat(persisted.getStatusUpdatedAt()).isNull();
    }

    @Test
    void statusChange_withoutCsrfToken_isForbiddenEvenToStaff() throws Exception {
        MockHttpSession resident = registerResident("status-nocsrf-owner@example.com");
        submitReport(resident, "Report the CSRF filter must protect");
        Long reportId = findByDescription("Report the CSRF filter must protect").getId();

        MockHttpSession staff = authenticateAs("status-nocsrf-staff@example.com", Role.STAFF);

        mockMvc.perform(post("/staff/reports/" + reportId + "/status")
                .param("status", "RESOLVED")
                .session(staff))
            .andExpect(status().isForbidden());

        Report persisted = reportRepository.findById(reportId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.NEW);
        assertThat(persisted.getStatusUpdatedAt()).isNull();
    }

    @Test
    void statusChange_isPermittedToAdmin() throws Exception {
        MockHttpSession resident = registerResident("status-admin-owner@example.com");
        submitReport(resident, "Report an admin triages");
        Long reportId = findByDescription("Report an admin triages").getId();

        MockHttpSession admin = authenticateAs("status-admin@example.com", Role.ADMIN);

        mockMvc.perform(post("/staff/reports/" + reportId + "/status")
                .param("status", "IN_PROGRESS")
                .session(admin)
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/staff/reports/" + reportId));

        assertThat(reportRepository.findById(reportId).orElseThrow().getStatus())
            .isEqualTo(ReportStatus.IN_PROGRESS);
    }

    private void changeStatus(MockHttpSession staff, Long reportId, String status) throws Exception {
        mockMvc.perform(post("/staff/reports/" + reportId + "/status")
                .param("status", status)
                .session(staff)
                .with(csrf()))
            .andExpect(status().is3xxRedirection());
    }
}
