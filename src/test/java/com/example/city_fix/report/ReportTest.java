package com.example.city_fix.report;

import com.example.city_fix.user.Role;
import com.example.city_fix.user.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ReportTest {

    private Report report() {
        User reporter = new User("reporter@example.com", "encoded-hash", Role.RESIDENT);
        return new Report(52.2297, 21.0122, "Pothole on the corner", Category.POTHOLE, reporter);
    }

    @Test
    void newReport_isNewWithNoStatusTimestamp() {
        Report report = report();

        assertThat(report.getStatus()).isEqualTo(ReportStatus.NEW);
        assertThat(report.getStatusUpdatedAt()).isNull();
    }

    @Test
    void changeStatus_setsStatusAndStampsTimestamp() {
        Report report = report();

        report.changeStatus(ReportStatus.IN_PROGRESS);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.IN_PROGRESS);
        assertThat(report.getStatusUpdatedAt()).isNotNull();
    }

    @Test
    void changeStatus_acceptsEveryTransitionBetweenDistinctStatuses() {
        for (ReportStatus from : ReportStatus.values()) {
            for (ReportStatus to : ReportStatus.values()) {
                if (from == to) {
                    continue;
                }
                Report report = report();
                report.changeStatus(from);
                report.changeStatus(to);

                assertThat(report.getStatus())
                        .as("transition %s -> %s", from, to)
                        .isEqualTo(to);
            }
        }
    }

    @Test
    void changeStatus_movesBackwardsOutOfATerminalLookingStatus() {
        Report report = report();
        report.changeStatus(ReportStatus.RESOLVED);

        report.changeStatus(ReportStatus.NEW);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.NEW);
    }

    @Test
    void changeStatus_rejectsNull() {
        Report report = report();

        assertThatNullPointerException().isThrownBy(() -> report.changeStatus(null));
        assertThat(report.getStatus()).isEqualTo(ReportStatus.NEW);
        assertThat(report.getStatusUpdatedAt()).isNull();
    }
}
