package org.pm.cardvault.card;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.pm.cardvault.crypto.CardCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CardRepository {

    private final JdbcTemplate jdbc;

    public CardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(CardMetadata card, CardCipher.Encrypted pan) {
        jdbc.update("""
                INSERT INTO card (token, pan_ciphertext, iv, key_version, fingerprint, last4, brand, expiry_month, expiry_year)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                card.token(), pan.ciphertext(), pan.iv(), pan.keyVersion(), card.fingerprint(), card.last4(),
                card.brand().name(), card.expiryMonth(), card.expiryYear());
    }

    Optional<CardMetadata> metadata(String token) {
        return jdbc.query("""
                        SELECT token, last4, brand, fingerprint, expiry_month, expiry_year, created_at
                        FROM card WHERE token = ?""",
                (rs, i) -> metadata(rs), token).stream().findFirst();
    }

    Optional<CardCipher.Encrypted> encryptedPan(String token) {
        return jdbc.query("SELECT pan_ciphertext, iv, key_version FROM card WHERE token = ?",
                (rs, i) -> new CardCipher.Encrypted(rs.getBytes(1), rs.getBytes(2), rs.getInt(3)), token)
                .stream().findFirst();
    }

    void audit(String token, String clientName) {
        jdbc.update("INSERT INTO detokenize_audit (id, token, client_name) VALUES (?, ?, ?)",
                UUID.randomUUID(), token, clientName);
    }

    private static CardMetadata metadata(ResultSet rs) throws SQLException {
        return new CardMetadata(rs.getString("token"), rs.getString("last4"), CardBrand.valueOf(rs.getString("brand")),
                rs.getString("fingerprint"), rs.getInt("expiry_month"), rs.getInt("expiry_year"),
                rs.getTimestamp("created_at").toInstant());
    }
}
