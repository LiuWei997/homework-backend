package com.example.demo.event;

public record ScheduleChanged(String taskId, long lockVersion) {
}
