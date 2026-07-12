package org.pm.patientservice.controller;

import org.junit.jupiter.api.Test;
import org.pm.patientservice.dto.PatientRequestDTO;
import org.pm.patientservice.dto.PatientResponseDTO;
import org.pm.patientservice.exception.EmailAlreadyExistsException;
import org.pm.patientservice.service.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PatientController.class) // load only the web layer for this one controller. No Tomcat, no DB, no Service/Repository beans.
                                     //This is what makes it a fast unit test.
class PatientControllerTest {

    @Autowired
    private MockMvc mockMvc; //MockMvc is a fake HTTP client; it "calls" /patients in memory, no real server.

    @MockitoBean  //put a Mockito fake of the service into Spring in place of the real one.
    private PatientService patientService; //  Controller = real, its dependency = puppet. old annotation now-deprecated @MockBean

    @Test
    void shouldReturnPatientListWithStatus200() throws Exception {

        // Arrange - the fake data the mock will return.
        PatientResponseDTO dto = new PatientResponseDTO();
        dto.setId(UUID.randomUUID().toString());
        dto.setName("John Doe");
        dto.setEmail("john@test.com");
        dto.setAddress("123 Main St");
        dto.setDateOfBirth("1990-01-01");

        when(patientService.getPatients()).thenReturn(List.of(dto)); //when asked for patients, hand back this list." No DB touched.

        // Act + Assert  ||  Act: fire a fake GET.
        mockMvc.perform(get("/patients"))
                .andExpect(status().isOk()) // assert HTTP 200.
                .andExpect(jsonPath("$.size()").value(1)) // $ = JSON root (an array); .size() = element count.
                .andExpect(jsonPath("$[0].email").value("john@test.com")); // first element's email is correct → proves serialization works.
    }

    @Test
    void shouldCreatePatientAndReturnStatus200() throws Exception {

        // Arrange
        PatientResponseDTO saved = new PatientResponseDTO();
        saved.setId(UUID.randomUUID().toString());
        saved.setName("John Doe");
        saved.setEmail("john@test.com");
        saved.setAddress("123 Main St");
        saved.setDateOfBirth("1990-01-01");

        when(patientService.createPatient(any(PatientRequestDTO.class)))
                .thenReturn(saved);

        String requestBody = """
                {
                    "name": "John Doe",
                    "email": "john@test.com",
                    "address": "123 Main St",
                    "dateOfBirth": "1990-01-01",
                    "registeredDate": "2024-01-01"
                }
                """;

        // Act + Assert
        mockMvc.perform(post("/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("john@test.com"));
    }

    @Test
    void shouldReturnStatus400WhenEmailAlreadyExists() throws Exception {

        // Arrange
        when(patientService.createPatient(any(PatientRequestDTO.class)))
                .thenThrow(new EmailAlreadyExistsException("email exists"));

        String requestBody = """
                {
                    "name": "John Doe",
                    "email": "john@test.com",
                    "address": "123 Main St",
                    "dateOfBirth": "1990-01-01",
                    "registeredDate": "2024-01-01"
                }
                """;

        // Act + Assert
        mockMvc.perform(post("/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Email address already exists"));
    }
}