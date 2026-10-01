package com.example.demo.controller;

import com.example.demo.scheduler.SchedulerWakeup;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SchedulerControllerTests {
    @Test
    void wakeupEndpointSignalsSchedulerAndReturnsNoContent() throws Exception {
        SchedulerWakeup wakeup = mock(SchedulerWakeup.class);
        MockMvc mockMvc = standaloneSetup(new SchedulerController(wakeup)).build();

        mockMvc.perform(post("/scheduler/wakeup"))
                .andExpect(status().isNoContent());

        verify(wakeup).signal();
    }
}
