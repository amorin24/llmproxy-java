package com.llmproxy.service.llm;

import com.llmproxy.model.ModelType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ModelVersionValidatorTest {

    private final ModelVersionValidator validator = new ModelVersionValidator();

    @Test
    void validateModelVersion_nullVersion_returnsDefaultVersion() {
        assertEquals(ModelVersionValidator.DEFAULT_OPENAI_VERSION, 
                validator.validateModelVersion(ModelType.OPENAI, null));
        assertEquals(ModelVersionValidator.DEFAULT_GEMINI_VERSION, 
                validator.validateModelVersion(ModelType.GEMINI, null));
        assertEquals(ModelVersionValidator.DEFAULT_MISTRAL_VERSION, 
                validator.validateModelVersion(ModelType.MISTRAL, null));
        assertEquals(ModelVersionValidator.DEFAULT_CLAUDE_VERSION, 
                validator.validateModelVersion(ModelType.CLAUDE, null));
    }

    @Test
    void validateModelVersion_emptyVersion_returnsDefaultVersion() {
        assertEquals(ModelVersionValidator.DEFAULT_OPENAI_VERSION, 
                validator.validateModelVersion(ModelType.OPENAI, ""));
        assertEquals(ModelVersionValidator.DEFAULT_GEMINI_VERSION, 
                validator.validateModelVersion(ModelType.GEMINI, ""));
        assertEquals(ModelVersionValidator.DEFAULT_MISTRAL_VERSION, 
                validator.validateModelVersion(ModelType.MISTRAL, ""));
        assertEquals(ModelVersionValidator.DEFAULT_CLAUDE_VERSION, 
                validator.validateModelVersion(ModelType.CLAUDE, ""));
    }

    @ParameterizedTest
    @MethodSource("provideValidModelVersions")
    void validateModelVersion_validVersion_returnsVersion(ModelType modelType, String version) {
        assertEquals(version, validator.validateModelVersion(modelType, version));
    }

    @ParameterizedTest
    @MethodSource("provideInvalidModelVersions")
    void validateModelVersion_invalidVersion_returnsDefaultVersion(ModelType modelType, String version) {
        String defaultVersion = switch (modelType) {
            case OPENAI -> ModelVersionValidator.DEFAULT_OPENAI_VERSION;
            case GEMINI -> ModelVersionValidator.DEFAULT_GEMINI_VERSION;
            case MISTRAL -> ModelVersionValidator.DEFAULT_MISTRAL_VERSION;
            case CLAUDE -> ModelVersionValidator.DEFAULT_CLAUDE_VERSION;
        };
        
        assertEquals(defaultVersion, validator.validateModelVersion(modelType, version));
    }

    @Test
    void getSupportedVersionsForModel_returnsCorrectVersions() {
        assertNotNull(validator.getSupportedVersionsForModel(ModelType.OPENAI));
        assertNotNull(validator.getSupportedVersionsForModel(ModelType.GEMINI));
        assertNotNull(validator.getSupportedVersionsForModel(ModelType.MISTRAL));
        assertNotNull(validator.getSupportedVersionsForModel(ModelType.CLAUDE));
        
        assertTrue(validator.getSupportedVersionsForModel(ModelType.OPENAI).contains("gpt-4o"));
        assertTrue(validator.getSupportedVersionsForModel(ModelType.GEMINI).contains("gemini-1.5-pro"));
        assertTrue(validator.getSupportedVersionsForModel(ModelType.MISTRAL).contains("mistral-large-latest"));
        assertTrue(validator.getSupportedVersionsForModel(ModelType.CLAUDE).contains("claude-3-sonnet"));
        
        assertTrue(validator.getSupportedVersionsForModel(ModelType.OPENAI).contains("gpt-4o-mini"));
        assertTrue(validator.getSupportedVersionsForModel(ModelType.GEMINI).contains("gemini-2.5-flash-preview-04-17"));
        assertTrue(validator.getSupportedVersionsForModel(ModelType.MISTRAL).contains("codestral-latest"));
        assertTrue(validator.getSupportedVersionsForModel(ModelType.CLAUDE).contains("claude-3-opus-20240229"));
    }

    private static Stream<Arguments> provideValidModelVersions() {
        return Stream.of(
                Arguments.of(ModelType.OPENAI, "gpt-4o"),
                Arguments.of(ModelType.OPENAI, "gpt-3.5-turbo"),
                Arguments.of(ModelType.GEMINI, "gemini-1.5-pro"),
                Arguments.of(ModelType.GEMINI, "gemini-2.0-flash"),
                Arguments.of(ModelType.MISTRAL, "mistral-large-latest"),
                Arguments.of(ModelType.MISTRAL, "codestral-latest"),
                Arguments.of(ModelType.CLAUDE, "claude-3-sonnet"),
                Arguments.of(ModelType.CLAUDE, "claude-3-opus-20240229")
        );
    }

    private static Stream<Arguments> provideInvalidModelVersions() {
        // Valid versions from each model type (to be used as INVALID for other types)
        List<String> openaiVersions = List.of(
                "gpt-4o",
                "gpt-4o-mini",
                "gpt-4-turbo",
                "gpt-4",
                "gpt-4-vision-preview",
                "gpt-3.5-turbo",
                "gpt-3.5-turbo-16k"
        );
        List<String> geminiVersions = List.of(
                "gemini-2.5-flash-preview-04-17",
                "gemini-2.5-pro-preview-03-25",
                "gemini-2.0-flash",
                "gemini-2.0-flash-lite",
                "gemini-1.5-flash",
                "gemini-1.5-flash-8b",
                "gemini-1.5-pro",
                "gemini-pro",
                "gemini-pro-vision"
        );
        List<String> mistralVersions = List.of(
                "codestral-latest",
                "mistral-large-latest",
                "mistral-saba-latest",
                "mistral-tiny",
                "mistral-small",
                "mistral-medium",
                "mistral-large"
        );
        List<String> claudeVersions = List.of(
                "claude-3-opus-20240229",
                "claude-3-sonnet-20240229",
                "claude-3-haiku-20240307",
                "claude-3-opus",
                "claude-3-sonnet",
                "claude-3-haiku",
                "claude-2.1",
                "claude-2.0"
        );

        // Semantically plausible but non-existent versions per model type
        List<String> plausibleOpenai = List.of(
                "gpt-5-turbo",         // plausible, but not in supported list
                "gpt-4o-2024",         // plausible pattern
                "gpt-3.9-turbo",       // plausible between existing versions
                "gpt-4-ultimate"       // plausible tier
        );
        List<String> plausibleGemini = List.of(
                "gemini-1.5-ultra",      // not listed
                "gemini-2.1-flash",      // plausible next
                "gemini-1.6-pro",        // plausible sequential
                "gemini-super-vision"    // plausible name
        );
        List<String> plausibleMistral = List.of(
                "mistral-huge",            // no such variant
                "codestral-2025",          // plausible year update
                "mistral-medium-8x",       // plausible config
                "mistral-extra-large"      // plausible name
        );
        List<String> plausibleClaude = List.of(
                "claude-4-opus",           // version bump
                "claude-3-haiku-20240501", // extended pattern
                "claude-3-giant",          // plausible name
                "claude-2.2"               // plausible version bump
        );

        Stream.Builder<Arguments> builder = Stream.builder();

        // Classic 'totally unrecognized' string and prior test cases
        builder.add(Arguments.of(ModelType.OPENAI, "invalid-model"));
        builder.add(Arguments.of(ModelType.GEMINI, "gpt-4"));
        builder.add(Arguments.of(ModelType.MISTRAL, "claude-3"));
        builder.add(Arguments.of(ModelType.CLAUDE, "gemini-pro"));

        // Cross-model valid version inputs (should be INVALID for the given type)
        for (String v : geminiVersions) {
            builder.add(Arguments.of(ModelType.OPENAI, v));
        }
        for (String v : mistralVersions) {
            builder.add(Arguments.of(ModelType.OPENAI, v));
        }
        for (String v : claudeVersions) {
            builder.add(Arguments.of(ModelType.OPENAI, v));
        }
        for (String v : openaiVersions) {
            builder.add(Arguments.of(ModelType.GEMINI, v));
        }
        for (String v : mistralVersions) {
            builder.add(Arguments.of(ModelType.GEMINI, v));
        }
        for (String v : claudeVersions) {
            builder.add(Arguments.of(ModelType.GEMINI, v));
        }
        for (String v : openaiVersions) {
            builder.add(Arguments.of(ModelType.MISTRAL, v));
        }
        for (String v : geminiVersions) {
            builder.add(Arguments.of(ModelType.MISTRAL, v));
        }
        for (String v : claudeVersions) {
            builder.add(Arguments.of(ModelType.MISTRAL, v));
        }
        for (String v : openaiVersions) {
            builder.add(Arguments.of(ModelType.CLAUDE, v));
        }
        for (String v : geminiVersions) {
            builder.add(Arguments.of(ModelType.CLAUDE, v));
        }
        for (String v : mistralVersions) {
            builder.add(Arguments.of(ModelType.CLAUDE, v));
        }

        // Semantically plausible but non-existent version strings
        for (String v : plausibleOpenai) {
            builder.add(Arguments.of(ModelType.OPENAI, v));
        }
        for (String v : plausibleGemini) {
            builder.add(Arguments.of(ModelType.GEMINI, v));
        }
        for (String v : plausibleMistral) {
            builder.add(Arguments.of(ModelType.MISTRAL, v));
        }
        for (String v : plausibleClaude) {
            builder.add(Arguments.of(ModelType.CLAUDE, v));
        }

        // Some additional string-related edge cases
        builder.add(Arguments.of(ModelType.OPENAI, "Gpt-4o")); // case sensitivity
        builder.add(Arguments.of(ModelType.GEMINI, "Gemini-1.5-Pro")); // case sensitivity
        builder.add(Arguments.of(ModelType.MISTRAL, "MISTRAL-LARGE-LATEST")); // case sensitivity
        builder.add(Arguments.of(ModelType.CLAUDE, "CLAUDE-3-SONNET")); // case sensitivity

        builder.add(Arguments.of(ModelType.OPENAI, "gpt-4o!@#$")); // special chars
        builder.add(Arguments.of(ModelType.GEMINI, "gemini-1.5-pro?")); // special chars
        builder.add(Arguments.of(ModelType.MISTRAL, "mistral-large-latest/")); // special chars
        builder.add(Arguments.of(ModelType.CLAUDE, "claude-3-sonnet~")); // special chars

        builder.add(Arguments.of(ModelType.OPENAI, "gpt-4o" + "x".repeat(100))); // overly long
        builder.add(Arguments.of(ModelType.GEMINI, "gemini-2.5-pro-preview-03-25" + "y".repeat(200))); // overly long

        return builder.build();
    }
}