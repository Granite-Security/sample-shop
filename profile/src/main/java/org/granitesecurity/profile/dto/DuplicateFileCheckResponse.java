package org.granitesecurity.profile.dto;

public record DuplicateFileCheckResponse(boolean duplicate, UserFileResponse existingFile) {
}
