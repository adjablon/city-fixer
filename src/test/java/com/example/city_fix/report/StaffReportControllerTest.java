package com.example.city_fix.report;

import com.example.city_fix.TestcontainersConfig;
import com.example.city_fix.user.Role;
import com.example.city_fix.user.User;
import com.example.city_fix.user.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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

@SpringBootTest
@AutoConfigureMockMvc
class StaffReportControllerTest extends TestcontainersConfig {

    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

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
    void staffRoutes_unauthenticated_redirectToLogin() throws Exception {
        mockMvc.perform(get("/staff/reports"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/login"));

        mockMvc.perform(get("/staff/reports/1"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/login"));
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
    void reportCreation_isForbiddenToStaff() throws Exception {
        MockHttpSession staff = authenticateAs("no-filing-staff@example.com", Role.STAFF);

        mockMvc.perform(get("/reports/new").session(staff))
            .andExpect(status().isForbidden());

        mockMvc.perform(multipart("/reports")
                .param("latitude", "52.100000")
                .param("longitude", "21.000000")
                .param("description", "Staff should not be able to file this")
                .param("category", "POTHOLE")
                .session(staff)
                .with(csrf()))
            .andExpect(status().isForbidden());
    }

    private void changeStatus(MockHttpSession staff, Long reportId, String status) throws Exception {
        mockMvc.perform(post("/staff/reports/" + reportId + "/status")
                .param("status", status)
                .session(staff)
                .with(csrf()))
            .andExpect(status().is3xxRedirection());
    }

    /** STAFF and ADMIN cannot be registered through the API, so the row is written directly. */
    private MockHttpSession authenticateAs(String email, Role role) throws Exception {
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), role));
        return login(email);
    }

    private MockHttpSession registerResident(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
            .andExpect(status().isCreated());
        return login(email);
    }

    private MockHttpSession login(String email) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
            .andExpect(status().isOk())
            .andReturn();
        return (MockHttpSession) login.getRequest().getSession();
    }

    private static String credentials(String email) {
        return """
            {"email": "%s", "password": "%s"}
            """.formatted(email, PASSWORD);
    }

    private void submitReport(MockHttpSession session, String description) throws Exception {
        mockMvc.perform(multipart("/reports")
                .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", jpegBytes()))
                .param("latitude", "52.100000")
                .param("longitude", "21.000000")
                .param("description", description)
                .param("category", "POTHOLE")
                .session(session)
                .with(csrf()))
            .andExpect(status().is3xxRedirection());
    }

    private Report findByDescription(String description) {
        return reportRepository.findAll().stream()
            .filter(report -> description.equals(report.getDescription()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No report persisted with description: " + description));
    }

    private static byte[] jpegBytes() {
        byte[] content = new byte[64];
        content[0] = (byte) 0xFF;
        content[1] = (byte) 0xD8;
        content[2] = (byte) 0xFF;
        return content;
    }
}
