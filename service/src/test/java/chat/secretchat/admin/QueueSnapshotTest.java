package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class QueueSnapshotTest {
    @Test void emptyQueueHasNoWaitTime() {
        assertThat(QueueSnapshot.at(0, null, 10000).oldestWaitSeconds()).isNull();
    }

    @Test void reportsDepthAndWholeSecondsSinceOldestEnvelope() {
        QueueSnapshot snapshot = QueueSnapshot.at(2, 4200L, 10000);
        assertThat(snapshot.pendingCount()).isEqualTo(2);
        assertThat(snapshot.oldestWaitSeconds()).isEqualTo(5);
    }

    @Test void clockSkewDoesNotProduceNegativeWait() {
        assertThat(QueueSnapshot.at(1, 15000L, 10000).oldestWaitSeconds()).isZero();
    }
}
