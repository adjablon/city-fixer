package com.example.city_fix.report;

import com.example.city_fix.TestcontainersConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
class ReportWebControllerTest extends TestcontainersConfig {

    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private ReportPhotoRepository reportPhotoRepository;

    @Test
    void submitWithPhoto_redirectsAndPersistsReportWithPhoto() throws Exception {
        MockHttpSession session = authenticate("submit-photo@example.com");

        mockMvc.perform(multipart("/reports")
                .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", jpegBytes()))
                .param("latitude", "52.100000")
                .param("longitude", "21.000000")
                .param("description", "Pothole with a photo")
                .param("category", "POTHOLE")
                .session(session)
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/reports"));

        Report saved = findByDescription("Pothole with a photo");
        assertThat(saved.getStatus()).isEqualTo(ReportStatus.NEW);
        assertThat(saved.getCategory()).isEqualTo(Category.POTHOLE);
        assertThat(saved.getLatitude()).isEqualTo(52.1);
        assertThat(saved.getLongitude()).isEqualTo(21.0);
        assertThat(reportPhotoRepository.existsByReportId(saved.getId())).isTrue();
    }

    @Test
    void submitWithoutPhoto_redirectsAndPersistsNoPhotoRow() throws Exception {
        MockHttpSession session = authenticate("submit-nophoto@example.com");

        mockMvc.perform(multipart("/reports")
                .file(new MockMultipartFile("photo", "", "application/octet-stream", new byte[0]))
                .param("latitude", "52.110000")
                .param("longitude", "21.010000")
                .param("description", "Streetlight without a photo")
                .param("category", "STREETLIGHT")
                .session(session)
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/reports"));

        Report saved = findByDescription("Streetlight without a photo");
        assertThat(reportPhotoRepository.existsByReportId(saved.getId())).isFalse();
    }

    @Test
    void submitWithoutCoordinates_reRendersFormAndPersistsNothing() throws Exception {
        MockHttpSession session = authenticate("submit-nocoords@example.com");

        mockMvc.perform(multipart("/reports")
                .param("description", "Report without coordinates")
                .param("category", "POTHOLE")
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("report-new"))
            .andExpect(model().attributeExists("error"));

        assertThat(reportRepository.findAll())
            .noneMatch(report -> "Report without coordinates".equals(report.getDescription()));
    }

    @Test
    void submitOversizePhoto_reRendersFormKeepingThePinAndPersistsNothing() throws Exception {
        MockHttpSession session = authenticate("submit-oversize@example.com");
        byte[] oversize = new byte[2 * 1024 * 1024 + 1];
        oversize[0] = (byte) 0xFF;
        oversize[1] = (byte) 0xD8;
        oversize[2] = (byte) 0xFF;

        MvcResult result = mockMvc.perform(multipart("/reports")
                .file(new MockMultipartFile("photo", "huge.jpg", "image/jpeg", oversize))
                .param("latitude", "52.120000")
                .param("longitude", "21.020000")
                .param("description", "Report with an oversize photo")
                .param("category", "TRASH")
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("report-new"))
            .andExpect(model().attributeExists("error"))
            .andReturn();

        ReportForm redisplayed = (ReportForm) result.getModelAndView().getModel().get("reportForm");
        assertThat(redisplayed.latitude()).isEqualTo(52.12);
        assertThat(redisplayed.description()).isEqualTo("Report with an oversize photo");
        assertThat(reportRepository.findAll())
            .noneMatch(report -> "Report with an oversize photo".equals(report.getDescription()));
    }

    @Test
    void submitSpoofedImage_reRendersFormAndPersistsNothing() throws Exception {
        MockHttpSession session = authenticate("submit-spoofed@example.com");

        mockMvc.perform(multipart("/reports")
                .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", "#!/bin/sh".getBytes()))
                .param("latitude", "52.130000")
                .param("longitude", "21.030000")
                .param("description", "Report with a spoofed image")
                .param("category", "OTHER")
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("report-new"))
            .andExpect(model().attributeExists("error"));

        assertThat(reportRepository.findAll())
            .noneMatch(report -> "Report with a spoofed image".equals(report.getDescription()));
    }

    @Test
    void listReports_showsOnlyTheCallersOwnReports() throws Exception {
        MockHttpSession owner = authenticate("list-owner@example.com");
        MockHttpSession stranger = authenticate("list-stranger@example.com");
        submitReport(owner, "52.140000", "21.040000", "Owner's only report", "GRAFFITI");
        submitReport(stranger, "52.150000", "21.050000", "Stranger's only report", "SIGN");

        MvcResult result = mockMvc.perform(get("/reports").session(owner))
            .andExpect(status().isOk())
            .andReturn();

        @SuppressWarnings("unchecked")
        List<Report> visible = (List<Report>) result.getModelAndView().getModel().get("reports");
        assertThat(visible).extracting(Report::getDescription).containsExactly("Owner's only report");
    }

    @Test
    void detailAndPhotoForOwnReport_return200() throws Exception {
        MockHttpSession session = authenticate("own-detail@example.com");
        submitReport(session, "52.160000", "21.060000", "My own detailed report", "POTHOLE");
        Long reportId = findByDescription("My own detailed report").getId();

        mockMvc.perform(get("/reports/" + reportId).session(session))
            .andExpect(status().isOk())
            .andExpect(view().name("report-detail"))
            .andExpect(model().attribute("hasPhoto", true));

        mockMvc.perform(get("/reports/" + reportId + "/photo").session(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/jpeg"))
            .andExpect(header().string("Content-Disposition", "inline"));
    }

    @Test
    void detailForAnotherUsersReport_returns404() throws Exception {
        MockHttpSession owner = authenticate("cross-owner@example.com");
        MockHttpSession stranger = authenticate("cross-stranger@example.com");
        submitReport(owner, "52.170000", "21.070000", "Report the stranger must not see", "TRASH");
        Long reportId = findByDescription("Report the stranger must not see").getId();

        mockMvc.perform(get("/reports/" + reportId).session(stranger))
            .andExpect(status().isNotFound());
    }

    @Test
    void photoForAnotherUsersReport_returns404() throws Exception {
        MockHttpSession owner = authenticate("cross-photo-owner@example.com");
        MockHttpSession stranger = authenticate("cross-photo-stranger@example.com");
        submitReport(owner, "52.180000", "21.080000", "Photo the stranger must not fetch", "OTHER");
        Long reportId = findByDescription("Photo the stranger must not fetch").getId();

        mockMvc.perform(get("/reports/" + reportId + "/photo").session(stranger))
            .andExpect(status().isNotFound());
    }

    @Test
    void reportRoutesUnauthenticated_redirectToLogin() throws Exception {
        mockMvc.perform(get("/reports"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/login"));

        mockMvc.perform(get("/reports/new"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/login"));

        mockMvc.perform(get("/reports/1"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/login"));

        mockMvc.perform(get("/reports/1/photo"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/login"));
    }

    private MockHttpSession authenticate(String email) throws Exception {
        String credentials = """
            {"email": "%s", "password": "%s"}
            """.formatted(email, PASSWORD);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials))
            .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials))
            .andExpect(status().isOk())
            .andReturn();

        return (MockHttpSession) login.getRequest().getSession();
    }

    private void submitReport(MockHttpSession session,
                              String latitude,
                              String longitude,
                              String description,
                              String category) throws Exception {
        mockMvc.perform(multipart("/reports")
                .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", jpegBytes()))
                .param("latitude", latitude)
                .param("longitude", longitude)
                .param("description", description)
                .param("category", category)
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
