package org.pm.billingservice.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.pm.billingservice.money.Money;

/** One side of a journal entry: "DEBIT account X ₹100". Created only through JournalEntry. */
@Entity
@Immutable // append-only table: Hibernate must never issue an UPDATE
@Table(name = "entry_line")
public class EntryLine {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private JournalEntry journalEntry;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ledger_account_id", nullable = false)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private Currency currency;

    protected EntryLine() {}   // JPA needs a no-arg constructor

    EntryLine(JournalEntry journalEntry, int lineNo, LedgerAccount account, Direction direction, Money amount) {
        this.id = UUID.randomUUID();
        this.journalEntry = journalEntry;
        this.lineNo = lineNo;
        this.account = account;
        this.direction = direction;
        this.amountMinor = amount.amountMinor();
        this.currency = amount.currency();
    }

    public UUID getId() { return id; }
    public int getLineNo() { return lineNo; }
    public LedgerAccount getAccount() { return account; }
    public Direction getDirection() { return direction; }
    public Money getAmount() { return new Money(amountMinor, currency); }
}
