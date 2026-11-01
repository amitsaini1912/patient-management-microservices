package org.pm.billingservice.audit;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Walks the whole chain (in pages, oldest first) and reports the FIRST row that doesn't fit:
 *   - a missing seq            -> a row was deleted (or inserted with a made-up number)
 *   - prev_hash != hash before -> a row was deleted, inserted or reordered
 *   - hash != HMAC(row)        -> the row's content (or its hash) was changed
 *   - chain ends before head   -> rows were cut off the end (or the head was moved)
 * Reads the head first and checks only rows up to it: appends that commit meanwhile are simply not looked at,
 * so verify needs no lock and never blocks payments.
 *
 * With an anchor (ADR 0033, the head published to Kafka earlier) it also catches the one thing the chain alone can't:
 * the newest rows deleted AND the head moved back - the head must not be behind the anchor, and the row at the
 * anchor's seq must still have the anchor's hash.
 */
@Component
public class AuditVerifier {

    private static final int PAGE = 500;

    private final AuditLog log;

    public AuditVerifier(AuditLog log) {
        this.log = log;
    }

    /**
     * @param firstBrokenSeq null when valid
     * @param anchorSeq      the published anchor checked against, null when none was (none yet, or Kafka unreadable)
     * @param anchorNote     why no anchor was checked, null when one was
     */
    public record Result(boolean valid, long rowsChecked, long headSeq, Long firstBrokenSeq, String reason,
                         Long anchorSeq, String anchorNote) {

        static Result ok(long checked, long headSeq) {
            return new Result(true, checked, headSeq, null, null, null, null);
        }

        static Result broken(long checked, long headSeq, long seq, String reason) {
            return new Result(false, checked, headSeq, seq, reason, null, null);
        }

        public Result withAnchor(Long seq, String note) {
            return new Result(valid, rowsChecked, headSeq, firstBrokenSeq, reason, seq, note);
        }
    }

    /** The chain alone (no anchor): catches every change except a cut-off end with the head moved back. */
    public Result verify() {
        return verify(null);
    }

    /** The chain, and - when {@code anchor} isn't null - the chain against what was published outside the database. */
    public Result verify(AuditAnchors.Anchor anchor) {
        AuditLog.Head head = log.head();
        AuditHasher hasher = log.hasher();
        long expectedSeq = 1;
        String prevHash = AuditHasher.GENESIS;
        long checked = 0;

        while (true) {
            List<AuditEntry> page = log.page(expectedSeq - 1, head.lastSeq(), PAGE);
            if (page.isEmpty()) {
                break;
            }
            for (AuditEntry row : page) {
                if (row.seq() != expectedSeq) {
                    return Result.broken(checked, head.lastSeq(), expectedSeq,
                            "row " + expectedSeq + " is missing (next row is " + row.seq() + ")");
                }
                if (!row.prevHash().equals(prevHash)) {
                    return Result.broken(checked, head.lastSeq(), row.seq(), "prev_hash doesn't match the previous row's hash");
                }
                if (!hasher.hash(row).equals(row.hash())) {
                    return Result.broken(checked, head.lastSeq(), row.seq(), "row content doesn't match its hash");
                }
                if (anchor != null && row.seq() == anchor.seq() && !row.hash().equals(anchor.hash())) {
                    return Result.broken(checked, head.lastSeq(), row.seq(),
                            "row " + row.seq() + " doesn't match the anchor published outside the database")
                            .withAnchor(anchor.seq(), null);
                }
                checked++;
                prevHash = row.hash();
                expectedSeq++;
            }
        }
        long lastSeen = expectedSeq - 1;
        if (lastSeen != head.lastSeq()) {
            return Result.broken(checked, head.lastSeq(), lastSeen + 1,
                    "chain ends at row " + lastSeen + " but the head says " + head.lastSeq() + " (rows cut off the end)");
        }
        if (!prevHash.equals(head.lastHash())) {
            return Result.broken(checked, head.lastSeq(), head.lastSeq(), "the head's hash doesn't match the last row");
        }
        if (anchor != null && anchor.seq() > head.lastSeq()) {
            return Result.broken(checked, head.lastSeq(), head.lastSeq() + 1, "the chain ends at row " + head.lastSeq()
                    + " but row " + anchor.seq() + " was published as the head at " + anchor.at()
                    + " (rows cut off the end and the head moved back)").withAnchor(anchor.seq(), null);
        }
        return Result.ok(checked, head.lastSeq()).withAnchor(anchor == null ? null : anchor.seq(), null);
    }
}
