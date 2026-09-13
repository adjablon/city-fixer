package com.example.city_fix.report;

import com.example.city_fix.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class ReportWebControllerTest extends IntegrationTest {

    @Autowired
    private ReportPhotoRepository reportPhotoRepository;

    @Test
    void submitWithPhoto_redirectsAndPersistsReportWithPhoto() throws Exception {
        MockHttpSession session = registerResident("submit-photo@example.com");

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
    void submitWithPhoto_storesTheBytesUnchanged() throws Exception {
        MockHttpSession session = registerResident("submit-roundtrip@example.com");
        // Deliberately past the 255-byte default @Column length: a column mapped as
        // anything but bytea would truncate here, and ddl-auto=update cannot repair that
        // once the column exists.
        byte[] original = new byte[1024 * 1024];
        new java.util.Random(42).nextBytes(original);
        original[0] = (byte) 0xFF;
        original[1] = (byte) 0xD8;
        original[2] = (byte) 0xFF;

        mockMvc.perform(multipart("/reports")
                .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", original))
                .param("latitude", "52.190000")
                .param("longitude", "21.090000")
                .param("description", "Report whose photo must round-trip intact")
                .param("category", "POTHOLE")
                .session(session)
                .with(csrf()))
            .andExpect(status().is3xxRedirection());

        Report saved = findByDescription("Report whose photo must round-trip intact");
        ReportPhoto storedPhoto = reportPhotoRepository.findByReportId(saved.getId()).orElseThrow();
        assertThat(storedPhoto.getContentType()).isEqualTo("image/jpeg");
        assertThat(storedPhoto.getImageData()).isEqualTo(original);
    }

    @Test
    void submitWithoutPhoto_redirectsAndPersistsNoPhotoRow() throws Exception {
        MockHttpSession session = registerResident("submit-nophoto@example.com");

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
        MockHttpSession session = registerResident("submit-nocoords@example.com");

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
        MockHttpSession session = registerResident("submit-oversize@example.com");
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
        MockHttpSession session = registerResident("submit-spoofed@example.com");

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
        MockHttpSession owner = registerResident("list-owner@example.com");
        MockHttpSession stranger = registerResident("list-stranger@example.com");
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
        MockHttpSession session = registerResident("own-detail@example.com");
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
        MockHttpSession owner = registerResident("cross-owner@example.com");
        MockHttpSession stranger = registerResident("cross-stranger@example.com");
        submitReport(owner, "52.170000", "21.070000", "Report the stranger must not see", "TRASH");
        Long reportId = findByDescription("Report the stranger must not see").getId();

        mockMvc.perform(get("/reports/" + reportId).session(stranger))
            .andExpect(status().isNotFound());
    }

    @Test
    void photoForAnotherUsersReport_returns404() throws Exception {
        MockHttpSession owner = registerResident("cross-photo-owner@example.com");
        MockHttpSession stranger = registerResident("cross-photo-stranger@example.com");
        submitReport(owner, "52.180000", "21.080000", "Photo the stranger must not fetch", "OTHER");
        Long reportId = findByDescription("Photo the stranger must not fetch").getId();

        mockMvc.perform(get("/reports/" + reportId + "/photo").session(stranger))
            .andExpect(status().isNotFound());
    }
}
