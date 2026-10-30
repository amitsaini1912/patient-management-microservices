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
 */
@Component
public class AuditVerifier {

    private static final int PAGE = 500;

    private final AuditLog log;

    public AuditVerifier(AuditLog log) {
        this.log = log;
    }

    /** @param firstBrokenSeq null when valid */
    public record Result(boolean valid, long rowsChecked, long headSeq, Long firstBrokenSeq, String reason) {

        static Result ok(long checked, long headSeq) {
            return new Result(true, checked, headSeq, null, null);
        }

        static Result broken(long checked, long headSeq, long seq, String reason) {
            return new Result(false, checked, headSeq, seq, reason);
        }
    }

    public Result verify() {
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
        return Result.ok(checked, head.lastSeq());
    }
}
