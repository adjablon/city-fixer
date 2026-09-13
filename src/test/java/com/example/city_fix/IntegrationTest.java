package com.example.city_fix;

import com.example.city_fix.report.Report;
import com.example.city_fix.report.ReportRepository;
import com.example.city_fix.user.Role;
import com.example.city_fix.user.User;
import com.example.city_fix.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class for every MockMvc integration test, carrying the setup the suite previously
 * duplicated across four classes.
 *
 * <p>It deliberately does NOT declare a {@code @DynamicPropertySource} method: the customizer
 * Spring builds from one compares only the set of methods, so declaring a second would fork a
 * new application context even though it produces identical properties against the same
 * container. The inherited {@link TestcontainersConfig} method is the only one in the tree.
 *
 * <p>The database is shared across every test in the run and nothing is transactional, so each
 * caller must pass identifiers unique to its own test. No helper here defaults an email or a
 * report description for that reason.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest extends TestcontainersConfig {

    protected static final String PASSWORD = "password123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected ReportRepository reportRepository;

    protected static String credentials(String email) {
        return "{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}";
    }

    protected MockHttpSession login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
            .andExpect(status().isOk())
            .andReturn();
        return (MockHttpSession) result.getRequest().getSession();
    }

    /** Registration only ever yields a RESIDENT, so this is the resident-session helper. */
    protected MockHttpSession registerResident(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
            .andExpect(status().isCreated());
        return login(email);
    }

    /** STAFF and ADMIN cannot be registered through the API, so the row is written directly. */
    protected MockHttpSession authenticateAs(String email, Role role) throws Exception {
        persistUser(email, role, true);
        return login(email);
    }

    protected String persistUser(String email, Role role) {
        return persistUser(email, role, true);
    }

    protected String persistUser(String email, Role role, boolean active) {
        User user = new User(email, passwordEncoder.encode(PASSWORD), role);
        if (!active) {
            user.deactivate();
        }
        userRepository.save(user);
        return email;
    }

    protected void submitReport(MockHttpSession session, String description) throws Exception {
        submitReport(session, "52.100000", "21.000000", description, "POTHOLE");
    }

    protected void submitReport(MockHttpSession session,
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

    protected Report findByDescription(String description) {
        return reportRepository.findAll().stream()
            .filter(report -> description.equals(report.getDescription()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No report persisted with description: " + description));
    }

    protected static byte[] jpegBytes() {
        byte[] content = new byte[64];
        content[0] = (byte) 0xFF;
        content[1] = (byte) 0xD8;
        content[2] = (byte) 0xFF;
        return content;
    }
}
