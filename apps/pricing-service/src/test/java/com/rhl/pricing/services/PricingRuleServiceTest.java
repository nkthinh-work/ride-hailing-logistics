package com.rhl.pricing.services;

import com.rhl.pricing.dtos.CreatePricingRuleRequest;
import com.rhl.pricing.entities.PricingRule;
import com.rhl.pricing.entities.PricingRuleAudit;
import com.rhl.pricing.exceptions.InvalidPricingRuleException;
import com.rhl.pricing.exceptions.PricingRuleConflictException;
import com.rhl.pricing.exceptions.PricingRuleNotFoundException;
import com.rhl.pricing.models.PricingAuditAction;
import com.rhl.pricing.models.PricingRuleStatus;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import com.rhl.pricing.repositories.PricingRuleAuditRepository;
import com.rhl.pricing.repositories.PricingRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PricingRuleServiceTest {

    @Mock
    private PricingRuleRepository pricingRuleRepository;

    @Mock
    private PricingRuleAuditRepository auditRepository;

    @Mock
    private PricingScopeLockManager scopeLockManager;

    @InjectMocks
    private PricingRuleService pricingRuleService;

    private UUID adminId;
    private Instant now;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    @Nested
    @DisplayName("Create & Publish Rule Tests")
    class CreateAndPublishTests {

        @Test
        @DisplayName("Successfully creates version 1 when no prior rules exist")
        void createFirstVersion_success() {
            CreatePricingRuleRequest request = new CreatePricingRuleRequest(
                    ServiceType.RIDE,
                    VehicleType.MOTORBIKE,
                    "DEFAULT",
                    new BigDecimal("12000"),
                    new BigDecimal("2.0"),
                    new BigDecimal("4500"),
                    new BigDecimal("400"),
                    new BigDecimal("12000"),
                    "VND",
                    now,
                    null,
                    "Initial motorbike rate"
            );

            when(pricingRuleRepository.findConflictingRules(any(), any(), any(), any(), any(), isNull()))
                    .thenReturn(Collections.emptyList());
            when(pricingRuleRepository.findMaxVersion(ServiceType.RIDE, VehicleType.MOTORBIKE, "DEFAULT"))
                    .thenReturn(0);
            when(pricingRuleRepository.save(any(PricingRule.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            PricingRule created = pricingRuleService.createAndPublishRule(request, adminId);

            assertThat(created.getVersion()).isEqualTo(1);
            assertThat(created.getServiceType()).isEqualTo(ServiceType.RIDE);
            assertThat(created.getVehicleType()).isEqualTo(VehicleType.MOTORBIKE);
            assertThat(created.getStatus()).isEqualTo(PricingRuleStatus.PUBLISHED);
            assertThat(created.getCreatedBy()).isEqualTo(adminId);

            // Verify advisory lock was acquired
            verify(scopeLockManager).acquireScopeLock("RIDE:MOTORBIKE:DEFAULT");

            // Verify audit log was recorded atomically
            ArgumentCaptor<PricingRuleAudit> auditCaptor = ArgumentCaptor.forClass(PricingRuleAudit.class);
            verify(auditRepository).save(auditCaptor.capture());
            PricingRuleAudit audit = auditCaptor.getValue();
            assertThat(audit.getRuleId()).isEqualTo(created.getId());
            assertThat(audit.getVersion()).isEqualTo(1);
            assertThat(audit.getAction()).isEqualTo(PricingAuditAction.RULE_PUBLISHED);
            assertThat(audit.getActorId()).isEqualTo(adminId);
            assertThat(audit.getReason()).isEqualTo("Initial motorbike rate");
        }

        @Test
        @DisplayName("Increments version to 2 when version 1 already exists")
        void createSubsequentVersion_incrementsVersion() {
            CreatePricingRuleRequest request = new CreatePricingRuleRequest(
                    ServiceType.RIDE,
                    VehicleType.CAR_4_SEAT,
                    "VN-HAN",
                    new BigDecimal("22000"),
                    new BigDecimal("2.0"),
                    new BigDecimal("12000"),
                    new BigDecimal("900"),
                    new BigDecimal("22000"),
                    "VND",
                    now.plus(7, ChronoUnit.DAYS),
                    null,
                    "Price increase version 2"
            );

            when(pricingRuleRepository.findConflictingRules(any(), any(), any(), any(), any(), isNull()))
                    .thenReturn(Collections.emptyList());
            when(pricingRuleRepository.findMaxVersion(ServiceType.RIDE, VehicleType.CAR_4_SEAT, "VN-HAN"))
                    .thenReturn(1);
            when(pricingRuleRepository.save(any(PricingRule.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            PricingRule created = pricingRuleService.createAndPublishRule(request, adminId);

            assertThat(created.getVersion()).isEqualTo(2);
            assertThat(created.getRegionId()).isEqualTo("VN-HAN");
        }
    }

    @Nested
    @DisplayName("Conflict Validation Tests (FR-PRI-004)")
    class ConflictValidationTests {

        @Test
        @DisplayName("Rejects overlapping rule for same service, vehicle and region")
        void overlappingInterval_throwsConflictException() {
            PricingRule existing = new PricingRule(
                    UUID.randomUUID(),
                    ServiceType.RIDE,
                    VehicleType.MOTORBIKE,
                    "DEFAULT",
                    1,
                    new BigDecimal("12000"),
                    new BigDecimal("2.0"),
                    new BigDecimal("4500"),
                    new BigDecimal("400"),
                    new BigDecimal("12000"),
                    "VND",
                    PricingRuleStatus.PUBLISHED,
                    now.minus(5, ChronoUnit.DAYS),
                    now.plus(5, ChronoUnit.DAYS),
                    now.minus(5, ChronoUnit.DAYS),
                    adminId
            );

            CreatePricingRuleRequest conflictingRequest = new CreatePricingRuleRequest(
                    ServiceType.RIDE,
                    VehicleType.MOTORBIKE,
                    "DEFAULT",
                    new BigDecimal("13000"),
                    new BigDecimal("2.0"),
                    new BigDecimal("5000"),
                    new BigDecimal("450"),
                    new BigDecimal("13000"),
                    "VND",
                    now, // overlaps with existing interval
                    now.plus(10, ChronoUnit.DAYS),
                    "Conflicting rule"
            );

            when(pricingRuleRepository.findConflictingRules(any(), any(), any(), any(), any(), isNull()))
                    .thenReturn(List.of(existing));

            assertThatThrownBy(() -> pricingRuleService.createAndPublishRule(conflictingRequest, adminId))
                    .isInstanceOf(PricingRuleConflictException.class)
                    .hasMessageContaining("conflict detected with existing rule v1");

            // Verify rule and audit were not saved
            verify(pricingRuleRepository, never()).save(any());
            verify(auditRepository, never()).save(any());
        }

        @Test
        @DisplayName("Rejects invalid effective time when effectiveTo is before or equal to effectiveFrom")
        void invalidEffectiveDates_throwsInvalidPricingRuleException() {
            CreatePricingRuleRequest request = new CreatePricingRuleRequest(
                    ServiceType.RIDE,
                    VehicleType.MOTORBIKE,
                    "DEFAULT",
                    new BigDecimal("12000"),
                    new BigDecimal("2.0"),
                    new BigDecimal("4500"),
                    new BigDecimal("400"),
                    new BigDecimal("12000"),
                    "VND",
                    now,
                    now.minus(1, ChronoUnit.HOURS), // invalid: before effectiveFrom
                    "Invalid time"
            );

            assertThatThrownBy(() -> pricingRuleService.createAndPublishRule(request, adminId))
                    .isInstanceOf(InvalidPricingRuleException.class)
                    .hasMessageContaining("must be strictly after effectiveFrom");
        }
    }

    @Nested
    @DisplayName("Archive Rule Tests")
    class ArchiveRuleTests {

        @Test
        @DisplayName("Archives published rule and appends audit log")
        void archiveRule_success() {
            UUID ruleId = UUID.randomUUID();
            PricingRule existing = new PricingRule(
                    ruleId,
                    ServiceType.RIDE,
                    VehicleType.CAR_7_SEAT,
                    "DEFAULT",
                    1,
                    new BigDecimal("25000"),
                    new BigDecimal("2.0"),
                    new BigDecimal("13000"),
                    new BigDecimal("1000"),
                    new BigDecimal("25000"),
                    "VND",
                    PricingRuleStatus.PUBLISHED,
                    now.minus(10, ChronoUnit.DAYS),
                    null,
                    now.minus(10, ChronoUnit.DAYS),
                    adminId
            );

            when(pricingRuleRepository.findById(ruleId)).thenReturn(Optional.of(existing));
            when(pricingRuleRepository.save(any(PricingRule.class))).thenAnswer(inv -> inv.getArgument(0));

            PricingRule archived = pricingRuleService.archiveRule(ruleId, "Seasonal rate retired", adminId);

            assertThat(archived.getStatus()).isEqualTo(PricingRuleStatus.ARCHIVED);
            assertThat(archived.getEffectiveTo()).isNotNull();

            // Verify advisory lock was acquired for archive
            verify(scopeLockManager).acquireScopeLock("RIDE:CAR_7_SEAT:DEFAULT");

            ArgumentCaptor<PricingRuleAudit> auditCaptor = ArgumentCaptor.forClass(PricingRuleAudit.class);
            verify(auditRepository).save(auditCaptor.capture());
            PricingRuleAudit audit = auditCaptor.getValue();
            assertThat(audit.getAction()).isEqualTo(PricingAuditAction.RULE_ARCHIVED);
            assertThat(audit.getReason()).isEqualTo("Seasonal rate retired");
        }

        @Test
        @DisplayName("Archiving future rule preserves effectiveTo to prevent effective_to < effective_from constraint violation")
        void archiveRule_futureEffectiveFrom_doesNotViolateConstraint() {
            UUID ruleId = UUID.randomUUID();
            Instant futureFrom = now.plus(30, ChronoUnit.DAYS);
            PricingRule futureRule = new PricingRule(
                    ruleId,
                    ServiceType.RIDE,
                    VehicleType.MOTORBIKE,
                    "DEFAULT",
                    2,
                    new BigDecimal("15000"),
                    new BigDecimal("2.0"),
                    new BigDecimal("5000"),
                    new BigDecimal("450"),
                    new BigDecimal("15000"),
                    "VND",
                    PricingRuleStatus.PUBLISHED,
                    futureFrom,
                    null,
                    now,
                    adminId
            );

            when(pricingRuleRepository.findById(ruleId)).thenReturn(Optional.of(futureRule));
            when(pricingRuleRepository.save(any(PricingRule.class))).thenAnswer(inv -> inv.getArgument(0));

            PricingRule archived = pricingRuleService.archiveRule(ruleId, "Cancelled future promotion", adminId);

            assertThat(archived.getStatus()).isEqualTo(PricingRuleStatus.ARCHIVED);
            // Must NOT be set to 'now' because now < futureFrom would violate database constraint
            assertThat(archived.getEffectiveTo()).isNull();
            verify(scopeLockManager).acquireScopeLock("RIDE:MOTORBIKE:DEFAULT");
        }

        @Test
        @DisplayName("Throws NotFoundException when archiving non-existent rule")
        void archiveNonExistent_throwsNotFound() {
            UUID randomId = UUID.randomUUID();
            when(pricingRuleRepository.findById(randomId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> pricingRuleService.archiveRule(randomId, "test", adminId))
                    .isInstanceOf(PricingRuleNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Publish Draft Rule Tests")
    class PublishDraftRuleTests {

        @Test
        @DisplayName("Successfully publishes draft rule with conflict check at publish time and monotonic version increment")
        void publishDraft_success() {
            UUID draftId = UUID.randomUUID();
            PricingRule draft = new PricingRule(
                    draftId,
                    ServiceType.RIDE,
                    VehicleType.CAR_4_SEAT,
                    "DEFAULT",
                    0, // unassigned draft version
                    new BigDecimal("20000"),
                    new BigDecimal("2.0"),
                    new BigDecimal("11000"),
                    new BigDecimal("800"),
                    new BigDecimal("20000"),
                    "VND",
                    PricingRuleStatus.DRAFT,
                    now,
                    null,
                    now,
                    adminId
            );

            when(pricingRuleRepository.findById(draftId)).thenReturn(Optional.of(draft));
            when(pricingRuleRepository.findConflictingRules(any(), any(), any(), any(), any(), eq(draftId)))
                    .thenReturn(Collections.emptyList());
            when(pricingRuleRepository.findMaxVersion(ServiceType.RIDE, VehicleType.CAR_4_SEAT, "DEFAULT"))
                    .thenReturn(2);
            when(pricingRuleRepository.save(any(PricingRule.class))).thenAnswer(inv -> inv.getArgument(0));

            PricingRule published = pricingRuleService.publishDraftRule(draftId, "Publish approved draft", adminId);

            assertThat(published.getStatus()).isEqualTo(PricingRuleStatus.PUBLISHED);
            assertThat(published.getVersion()).isEqualTo(3);
            verify(scopeLockManager).acquireScopeLock("RIDE:CAR_4_SEAT:DEFAULT");

            ArgumentCaptor<PricingRuleAudit> auditCaptor = ArgumentCaptor.forClass(PricingRuleAudit.class);
            verify(auditRepository).save(auditCaptor.capture());
            assertThat(auditCaptor.getValue().getAction()).isEqualTo(PricingAuditAction.RULE_PUBLISHED);
            assertThat(auditCaptor.getValue().getVersion()).isEqualTo(3);
        }

        @Test
        @DisplayName("Rejects publishing draft rule when conflict exists at publish time")
        void publishDraft_conflictAtPublishTime_throwsConflict() {
            UUID draftId = UUID.randomUUID();
            PricingRule draft = new PricingRule(
                    draftId,
                    ServiceType.RIDE,
                    VehicleType.CAR_4_SEAT,
                    "DEFAULT",
                    0,
                    new BigDecimal("20000"),
                    new BigDecimal("2.0"),
                    new BigDecimal("11000"),
                    new BigDecimal("800"),
                    new BigDecimal("20000"),
                    "VND",
                    PricingRuleStatus.DRAFT,
                    now,
                    null,
                    now,
                    adminId
            );

            PricingRule conflicting = new PricingRule(
                    UUID.randomUUID(),
                    ServiceType.RIDE,
                    VehicleType.CAR_4_SEAT,
                    "DEFAULT",
                    1,
                    new BigDecimal("20000"),
                    new BigDecimal("2.0"),
                    new BigDecimal("11000"),
                    new BigDecimal("800"),
                    new BigDecimal("20000"),
                    "VND",
                    PricingRuleStatus.PUBLISHED,
                    now.minus(5, ChronoUnit.DAYS),
                    null,
                    now.minus(5, ChronoUnit.DAYS),
                    adminId
            );

            when(pricingRuleRepository.findById(draftId)).thenReturn(Optional.of(draft));
            when(pricingRuleRepository.findConflictingRules(any(), any(), any(), any(), any(), eq(draftId)))
                    .thenReturn(List.of(conflicting));

            assertThatThrownBy(() -> pricingRuleService.publishDraftRule(draftId, "test", adminId))
                    .isInstanceOf(PricingRuleConflictException.class)
                    .hasMessageContaining("Pricing rule conflict detected with existing rule v1");
        }
    }
}
