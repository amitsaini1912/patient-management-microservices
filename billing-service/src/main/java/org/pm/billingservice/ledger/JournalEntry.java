package org.pm.billingservice.ledger;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.pm.billingservice.money.Money;
import org.springframework.data.domain.Persistable;

/**
 * One business event in the ledger, e.g. "patient charged ₹500":
 * DEBIT patient receivable ₹500 / CREDIT hospital revenue ₹500.
 *
 * Built as a draft (debit/credit), then post() checks the double-entry rules.
 * The database checks the same rules again at COMMIT (see V2__create_ledger.sql),
 * so a bug here, or a raw SQL insert, still can't store an unbalanced entry.
 */
@Entity
@Immutable // append-only table: Hibernate must never issue an UPDATE
@Table(name = "journal_entry")
public class JournalEntry implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String description;

    // the entry this one cancels, if it is a reversal
    @Column(name = "reversal_of")
    private UUID reversalOf;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    // what this entry records (null for manual entries and reversals); reconciliation matches on it
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type")
    private EntrySource sourceType;

    @Column(name = "source_id")
    private UUID sourceId;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.PERSIST)
    @OrderBy("lineNo")
    private List<EntryLine> lines = new ArrayList<>();

    // We assign the UUID ourselves, so Spring Data can't tell new from existing by "id == null".
    // Without this, save() calls merge() instead of persist(), and merge() doesn't cascade
    // PERSIST to the lines -> Hibernate tries to load lines that aren't in the DB yet.
    @Transient
    private boolean isNew = true;

    protected JournalEntry() {}   // JPA needs a no-arg constructor

    private JournalEntry(String description, UUID reversalOf) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        this.id = UUID.randomUUID();
        this.description = description;
        this.reversalOf = reversalOf;
    }

    public static JournalEntry draft(String description) {
        return new JournalEntry(description, null);
    }

    /** A draft that cancels {@code original}: the same lines with debit and credit swapped. */
    public static JournalEntry reversalOf(JournalEntry original, String description) {
        if (!original.isPosted()) {
            throw new IllegalStateException("only a posted entry can be reversed");
        }
        JournalEntry reversal = new JournalEntry(description, original.id);
        for (EntryLine line : original.lines) {
            reversal.addLine(line.getAccount(), line.getDirection().opposite(), line.getAmount());
        }
        return reversal;
    }

    /** Tags the draft with the payment/refund it records. The DB allows one entry per source. */
    public JournalEntry source(EntrySource type, UUID id) {
        if (isPosted()) {
            throw new IllegalStateException("entry " + this.id + " is already posted");
        }
        this.sourceType = Objects.requireNonNull(type, "source type is required");
        this.sourceId = Objects.requireNonNull(id, "source id is required");
        return this;
    }

    public JournalEntry debit(LedgerAccount account, Money amount) {
        return addLine(account, Direction.DEBIT, amount);
    }

    public JournalEntry credit(LedgerAccount account, Money amount) {
        return addLine(account, Direction.CREDIT, amount);
    }

    private JournalEntry addLine(LedgerAccount account, Direction direction, Money amount) {
        Objects.requireNonNull(account, "account is required");
        Objects.requireNonNull(amount, "amount is required");
        if (isPosted()) {
            throw new IllegalStateException("entry " + id + " is already posted; post a reversal instead");
        }
        if (!amount.isPositive()) {
            // the direction carries the sign; a negative amount would flip a debit into a hidden credit
            throw new IllegalArgumentException("line amount must be positive, was " + amount);
        }
        if (!amount.currency().equals(account.getCurrency())) {
            throw new IllegalArgumentException("account " + account.getId() + " is in " + account.getCurrency()
                    + " but the line is in " + amount.currency());
        }
        lines.add(new EntryLine(this, lines.size() + 1, account, direction, amount));
        return this;
    }

    /**
     * Checks the double-entry rules and freezes the entry. After this no line can be added.
     * Rules: at least two lines, and per currency, total debits = total credits.
     */
    public void post() {
        if (isPosted()) {
            throw new IllegalStateException("entry " + id + " is already posted");
        }
        if (lines.size() < 2) {
            throw new UnbalancedEntryException("entry needs at least 2 lines, has " + lines.size());
        }
        // signed total per currency: debits add, credits subtract. Money.plus throws on overflow.
        Map<Currency, Money> net = new HashMap<>();
        for (EntryLine line : lines) {
            Money signed = line.getDirection() == Direction.DEBIT ? line.getAmount() : line.getAmount().negate();
            net.merge(signed.currency(), signed, Money::plus);
        }
        for (Money total : net.values()) {
            if (!total.isZero()) {
                throw new UnbalancedEntryException("entry is unbalanced in " + total.currency().getCurrencyCode()
                        + ": debits - credits = " + total);
            }
        }
        this.postedAt = Instant.now();
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public boolean isPosted() {
        return postedAt != null;
    }

    @Override
    public UUID getId() { return id; }
    public String getDescription() { return description; }
    public UUID getReversalOf() { return reversalOf; }
    public Instant getPostedAt() { return postedAt; }
    public EntrySource getSourceType() { return sourceType; }
    public UUID getSourceId() { return sourceId; }
    public List<EntryLine> getLines() { return Collections.unmodifiableList(lines); }
}
