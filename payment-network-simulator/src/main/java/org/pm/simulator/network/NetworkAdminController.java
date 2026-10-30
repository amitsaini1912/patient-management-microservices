package org.pm.simulator.network;

import java.util.List;
import org.pm.simulator.issuer.Issuer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only views for checking by hand (API key required, like all /admin):
 *   GET /admin/network/messages   last ISO messages (PANs masked)
 *   GET /admin/issuer/accounts    issuer balances, holds, available (PANs masked)
 */
@RestController
public class NetworkAdminController {

    private final NetworkLog messages;
    private final Issuer issuer;

    public NetworkAdminController(NetworkLog messages, Issuer issuer) {
        this.messages = messages;
        this.issuer = issuer;
    }

    @GetMapping("/admin/network/messages")
    public List<NetworkLog.Entry> messages() {
        return messages.entries();
    }

    @GetMapping("/admin/issuer/accounts")
    public List<Issuer.AccountView> accounts() {
        return issuer.accounts();
    }
}
