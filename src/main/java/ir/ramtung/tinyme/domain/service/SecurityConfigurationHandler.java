package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.repository.SecurityRepository;
import org.springframework.stereotype.Service;

@Service
public class SecurityConfigurationHandler {

    SecurityRepository securityRepository;
    EventPublisher eventPublisher;

    public SecurityConfigurationHandler(SecurityRepository securityRepository, EventPublisher eventPublisher) {
        this.securityRepository = securityRepository;
        this.eventPublisher = eventPublisher;
    }

    public void handleMatchingStateRq(ChangeMatchingStateRq changeMatchingStateRq) {
        var security = this.securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        // Todo
    }
}
