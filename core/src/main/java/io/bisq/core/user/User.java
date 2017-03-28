/*
 * This file is part of bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bisq.core.user;

import io.bisq.common.app.Version;
import io.bisq.common.crypto.KeyRing;
import io.bisq.common.locale.LanguageUtil;
import io.bisq.common.locale.TradeCurrency;
import io.bisq.common.persistance.Persistable;
import io.bisq.common.storage.Storage;
import io.bisq.core.alert.Alert;
import io.bisq.core.arbitration.Arbitrator;
import io.bisq.core.arbitration.Mediator;
import io.bisq.core.filter.Filter;
import io.bisq.core.payment.PaymentAccount;
import io.bisq.network.p2p.NodeAddress;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;


/**
 * The User is persisted locally.
 * It must never be transmitted over the wire (messageKeyPair contains private key!).
 */
public final class User implements Persistable {
    // That object is saved to disc. We need to take care of changes to not break deserialization.
    private static final long serialVersionUID = Version.LOCAL_DB_VERSION;

    private static final Logger log = LoggerFactory.getLogger(User.class);

    // Transient immutable fields
    transient final private Storage<User> storage;
    transient private Set<TradeCurrency> tradeCurrenciesInPaymentAccounts;

    // Persisted fields
    private String accountID;
    private Set<PaymentAccount> paymentAccounts = new HashSet<>();
    private PaymentAccount currentPaymentAccount;
    private List<String> acceptedLanguageLocaleCodes = new ArrayList<>();
    @Nullable
    private Alert developersAlert;
    @Nullable
    private Alert displayedAlert;
    @Nullable
    private Filter developersFilter;

    private List<Arbitrator> acceptedArbitrators = new ArrayList<>();
    private List<Mediator> acceptedMediators = new ArrayList<>();

    @Nullable
    private Arbitrator registeredArbitrator;
    @Nullable
    private Mediator registeredMediator;

    // Observable wrappers
    transient final private ObservableSet<PaymentAccount> paymentAccountsAsObservable = FXCollections.observableSet(paymentAccounts);
    transient final private ObjectProperty<PaymentAccount> currentPaymentAccountProperty = new SimpleObjectProperty<>(currentPaymentAccount);


    @Inject
    public User(Storage<User> storage, KeyRing keyRing) throws NoSuchAlgorithmException {
        this.storage = storage;

        User persisted = storage.initAndGetPersisted(this);
        if (persisted != null) {
            accountID = persisted.getAccountId();

            // The check is only needed to not break old versions where paymentAccounts was not included and is null,
            // Can be removed later
            if (persisted.getPaymentAccounts() != null)
                paymentAccounts = new HashSet<>(persisted.getPaymentAccounts());

            paymentAccountsAsObservable.addAll(paymentAccounts);

            currentPaymentAccount = persisted.getCurrentPaymentAccount();
            currentPaymentAccountProperty.set(currentPaymentAccount);

            acceptedLanguageLocaleCodes = persisted.getAcceptedLanguageLocaleCodes();
            if (persisted.getAcceptedArbitrators() != null)
                acceptedArbitrators = persisted.getAcceptedArbitrators();
            if (persisted.getAcceptedMediators() != null)
                acceptedMediators = persisted.getAcceptedMediators();

            registeredArbitrator = persisted.getRegisteredArbitrator();
            registeredMediator = persisted.getRegisteredMediator();
            developersAlert = persisted.getDevelopersAlert();
            displayedAlert = persisted.getDisplayedAlert();
            developersFilter = persisted.getDevelopersFilter();
        } else {
            accountID = String.valueOf(Math.abs(keyRing.getPubKeyRing().hashCode()));

            acceptedLanguageLocaleCodes.add(LanguageUtil.getDefaultLanguageLocaleAsCode(Preferences.getDefaultLocale()));
            String english = LanguageUtil.getEnglishLanguageLocaleCode();
            if (!acceptedLanguageLocaleCodes.contains(english))
                acceptedLanguageLocaleCodes.add(english);

            acceptedArbitrators = new ArrayList<>();
            acceptedMediators = new ArrayList<>();
        }
        storage.queueUpForSave();

        // Use that to guarantee update of the serializable field and to make a storage update in case of a change
        paymentAccountsAsObservable.addListener((SetChangeListener<PaymentAccount>) change -> {
            paymentAccounts = new HashSet<>(paymentAccountsAsObservable);
            tradeCurrenciesInPaymentAccounts = paymentAccounts.stream().flatMap(e -> e.getTradeCurrencies().stream()).collect(Collectors.toSet());
            storage.queueUpForSave();
        });
        currentPaymentAccountProperty.addListener((ov) -> {
            currentPaymentAccount = currentPaymentAccountProperty.get();
            storage.queueUpForSave();
        });

        tradeCurrenciesInPaymentAccounts = paymentAccounts.stream().flatMap(e -> e.getTradeCurrencies().stream()).collect(Collectors.toSet());
    }

    // for unit tests
    public User() {
        this.storage = null;
    }


    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        try {
            in.defaultReadObject();
        } catch (Throwable t) {
            log.trace("Cannot be deserialized." + t.getMessage());
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public Methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addPaymentAccount(PaymentAccount paymentAccount) {
        paymentAccountsAsObservable.add(paymentAccount);
        setCurrentPaymentAccount(paymentAccount);
    }

    public void removePaymentAccount(PaymentAccount paymentAccount) {
        paymentAccountsAsObservable.remove(paymentAccount);
    }

    public void setCurrentPaymentAccount(PaymentAccount paymentAccount) {
        currentPaymentAccountProperty.set(paymentAccount);
    }

    public boolean addAcceptedLanguageLocale(String localeCode) {
        if (!acceptedLanguageLocaleCodes.contains(localeCode)) {
            boolean changed = acceptedLanguageLocaleCodes.add(localeCode);
            if (changed)
                storage.queueUpForSave();
            return changed;
        } else {
            return false;
        }
    }

    public boolean removeAcceptedLanguageLocale(String languageLocaleCode) {
        boolean changed = acceptedLanguageLocaleCodes.remove(languageLocaleCode);
        if (changed)
            storage.queueUpForSave();
        return changed;
    }

    public void addAcceptedArbitrator(Arbitrator arbitrator) {
        if (!acceptedArbitrators.contains(arbitrator) && !isMyOwnRegisteredArbitrator(arbitrator)) {
            boolean changed = acceptedArbitrators.add(arbitrator);
            if (changed)
                storage.queueUpForSave();
        }
    }

    public void addAcceptedMediator(Mediator mediator) {
        if (!acceptedMediators.contains(mediator) && !isMyOwnRegisteredMediator(mediator)) {
            boolean changed = acceptedMediators.add(mediator);
            if (changed)
                storage.queueUpForSave();
        }
    }

    public boolean isMyOwnRegisteredArbitrator(Arbitrator arbitrator) {
        return arbitrator.equals(registeredArbitrator);
    }

    public boolean isMyOwnRegisteredMediator(Mediator mediator) {
        return mediator.equals(registeredMediator);
    }

    public void removeAcceptedArbitrator(Arbitrator arbitrator) {
        boolean changed = acceptedArbitrators.remove(arbitrator);
        if (changed)
            storage.queueUpForSave();
    }

    public void clearAcceptedArbitrators() {
        acceptedArbitrators.clear();
        storage.queueUpForSave();
    }

    public void removeAcceptedMediator(Mediator mediator) {
        boolean changed = acceptedMediators.remove(mediator);
        if (changed)
            storage.queueUpForSave();
    }

    public void clearAcceptedMediators() {
        acceptedMediators.clear();
        storage.queueUpForSave();
    }

    public void setRegisteredArbitrator(@Nullable Arbitrator arbitrator) {
        this.registeredArbitrator = arbitrator;
        storage.queueUpForSave();
    }

    public void setRegisteredMediator(@Nullable Mediator mediator) {
        this.registeredMediator = mediator;
        storage.queueUpForSave();
    }

    public void setDevelopersFilter(@Nullable Filter developersFilter) {
        this.developersFilter = developersFilter;
        storage.queueUpForSave();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Nullable
    public PaymentAccount getPaymentAccount(String paymentAccountId) {
        Optional<PaymentAccount> optional = paymentAccounts.stream().filter(e -> e.getId().equals(paymentAccountId)).findAny();
        if (optional.isPresent())
            return optional.get();
        else
            return null;
    }

    public String getAccountId() {
        return accountID;
    }

   /* public boolean isRegistered() {
        return getAccountId() != null;
    }*/

    private PaymentAccount getCurrentPaymentAccount() {
        return currentPaymentAccount;
    }

    public ObjectProperty<PaymentAccount> currentPaymentAccountProperty() {
        return currentPaymentAccountProperty;
    }

    public Set<PaymentAccount> getPaymentAccounts() {
        return paymentAccounts;
    }

    public ObservableSet<PaymentAccount> getPaymentAccountsAsObservable() {
        return paymentAccountsAsObservable;
    }

    @Nullable
    public Arbitrator getRegisteredArbitrator() {
        return registeredArbitrator;
    }

    @Nullable
    public Mediator getRegisteredMediator() {
        return registeredMediator;
    }

    public List<Arbitrator> getAcceptedArbitrators() {
        return acceptedArbitrators;
    }

    public List<NodeAddress> getAcceptedArbitratorAddresses() {
        return acceptedArbitrators.stream().map(Arbitrator::getNodeAddress).collect(Collectors.toList());
    }

    public List<Mediator> getAcceptedMediators() {
        return acceptedMediators;
    }

    public List<NodeAddress> getAcceptedMediatorAddresses() {
        return acceptedMediators.stream().map(Mediator::getNodeAddress).collect(Collectors.toList());
    }

    public List<String> getAcceptedLanguageLocaleCodes() {
        return acceptedLanguageLocaleCodes != null ? acceptedLanguageLocaleCodes : new ArrayList<>();
    }

    public Arbitrator getAcceptedArbitratorByAddress(NodeAddress nodeAddress) {
        Optional<Arbitrator> arbitratorOptional = acceptedArbitrators.stream()
                .filter(e -> e.getNodeAddress().equals(nodeAddress))
                .findFirst();
        if (arbitratorOptional.isPresent())
            return arbitratorOptional.get();
        else
            return null;
    }

    public Mediator getAcceptedMediatorByAddress(NodeAddress nodeAddress) {
        Optional<Mediator> mediatorOptionalOptional = acceptedMediators.stream()
                .filter(e -> e.getNodeAddress().equals(nodeAddress))
                .findFirst();
        if (mediatorOptionalOptional.isPresent())
            return mediatorOptionalOptional.get();
        else
            return null;
    }

    @Nullable
    public Filter getDevelopersFilter() {
        return developersFilter;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

  /*  public Optional<TradeCurrency> getPaymentAccountForCurrency(TradeCurrency tradeCurrency) {
        return getPaymentAccounts().stream()
                .flatMap(e -> e.getTradeCurrencies().stream())
                .filter(e -> e.equals(tradeCurrency))
                .findFirst();
    }*/

    @Nullable
    public PaymentAccount findFirstPaymentAccountWithCurrency(TradeCurrency tradeCurrency) {
        for (PaymentAccount paymentAccount : paymentAccounts) {
            for (TradeCurrency tradeCurrency1 : paymentAccount.getTradeCurrencies()) {
                if (tradeCurrency1.equals(tradeCurrency))
                    return paymentAccount;
            }
        }
        return null;
    }

    public boolean hasMatchingLanguage(Arbitrator arbitrator) {
        if (arbitrator != null) {
            for (String acceptedCode : acceptedLanguageLocaleCodes) {
                for (String itemCode : arbitrator.getLanguageCodes()) {
                    if (acceptedCode.equals(itemCode))
                        return true;
                }
            }
        }
        return false;
    }

    public boolean hasPaymentAccountForCurrency(TradeCurrency tradeCurrency) {
        return findFirstPaymentAccountWithCurrency(tradeCurrency) != null;
    }

    public void setDevelopersAlert(@Nullable Alert developersAlert) {
        this.developersAlert = developersAlert;
        storage.queueUpForSave();
    }

    @Nullable
    public Alert getDevelopersAlert() {
        return developersAlert;
    }

    public void setDisplayedAlert(@Nullable Alert displayedAlert) {
        this.displayedAlert = displayedAlert;
        storage.queueUpForSave();
    }

    @Nullable
    public Alert getDisplayedAlert() {
        return displayedAlert;
    }
}
