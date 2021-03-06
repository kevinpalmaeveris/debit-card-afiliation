package com.everis.debitcardafiliation.service;

import com.everis.debitcardafiliation.constant.Constants;
import com.everis.debitcardafiliation.consumer.Webclient;
import com.everis.debitcardafiliation.dto.Message;
import com.everis.debitcardafiliation.dto.MovementFrom;
import com.everis.debitcardafiliation.map.AccountMapper;
import com.everis.debitcardafiliation.map.MovementsMapper;
import com.everis.debitcardafiliation.model.AccountAffiliate;
import com.everis.debitcardafiliation.model.DebitCard;
import com.everis.debitcardafiliation.repository.DebitCardRepository;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Transactional
@Slf4j
public class DebitCardService {

  @Autowired
  DebitCardRepository repository;

  private Boolean verifyCustomer(String id) {
    return Webclient.customer.get().uri(Constants.PathService.VERIFY_CUSTOMER, id).retrieve().bodyToMono(Boolean.class).block();
  }

  private Boolean verifyNumberCC(String number) {
    return Webclient.currentAccount
      .get()
      .uri(Constants.PathService.VERIFY_NUMBER_ACCOUNT.concat(number))
      .retrieve()
      .bodyToMono(Boolean.class)
      .block();
  }

  private Boolean verifyNumberSC(String number) {
    return Webclient.savingAccount.get().uri(Constants.PathService.VERIFY_NUMBER_ACCOUNT.concat(number)).retrieve().bodyToMono(Boolean.class).block();
  }

  private Boolean verifyNumberFC(String number) {
    return Webclient.fixedAccount.get().uri(Constants.PathService.VERIFY_NUMBER_ACCOUNT.concat(number)).retrieve().bodyToMono(Boolean.class).block();
  }

  private Boolean verifyAccount(String number) {
    return (verifyNumberCC(number) || verifyNumberSC(number) || verifyNumberFC(number));
  }

  private Boolean findAccountNumber(String id, String number) {
    return !repository
      .findById(id)
      .get()
      .getAccounts()
      .stream()
      .filter(c -> c.getNumberAccount().equals(number))
      .collect(Collectors.toList())
      .isEmpty();
  }

  private AccountMapper findCurrentAccountByNumber(String number) {
    return Webclient.currentAccount.get().uri(Constants.PathService.NUMBER_ACCOUNT, number).retrieve().bodyToMono(AccountMapper.class).block();
  }

  private AccountMapper findSavingAccountByNumber(String number) {
    return Webclient.savingAccount.get().uri(Constants.PathService.NUMBER_ACCOUNT, number).retrieve().bodyToMono(AccountMapper.class).block();
  }

  private AccountMapper findFixedAccountByNumber(String number) {
    return Webclient.fixedAccount.get().uri(Constants.PathService.NUMBER_ACCOUNT, number).retrieve().bodyToMono(AccountMapper.class).block();
  }

  private AccountMapper findByAccount(String number) {
    if (Boolean.TRUE.equals(verifyNumberSC(number))) {
      return findSavingAccountByNumber(number);
    } else if (Boolean.TRUE.equals(verifyNumberCC(number))) {
      return findCurrentAccountByNumber(number);
    } else {
      return findFixedAccountByNumber(number);
    }
  }

  private Boolean verifyDebitCard(String id, AccountAffiliate model) {
    try {
      return (
        repository.findAll().stream().filter(c -> c.getIdDebitCard().equals(id)).collect(Collectors.toList()).isEmpty() ||
        !verifyAccount(model.getNumberAccount()) ||
        !findByAccount(model.getNumberAccount()).getIdCustomer().equals(repository.findById(id).get().getIdCustomer())
      );
    } catch (Exception e) {
      return true;
    }
  }

  private MovementFrom authCard(MovementFrom model) {
    if (
      !repository
        .findAll()
        .stream()
        .filter(c -> c.getCardNumber().equals(model.getNumberDebitCard()) && c.getPassword().equals(model.getPassword()))
        .collect(Collectors.toList())
        .isEmpty()
    ) {
      return model;
    }
    return null;
  }

  private Double getAllAmount(String number) {
    return repository
      .findAll()
      .stream()
      .filter(c -> c.getCardNumber().equals(number))
      .findFirst()
      .get()
      .getAccounts()
      .stream()
      .mapToDouble(c -> findByAccount(c.getNumberAccount()).getAmount())
      .sum();
  }

  private void AddMovementAccount(String number, Double amount, String numberCard) {
    DebitCard card = repository.findAll().stream().filter(c -> c.getCardNumber().equals(numberCard)).findFirst().get();
    MovementsMapper model = new MovementsMapper(number, amount);

    if (verifyNumberSC(number)) {
      Webclient.savingAccount.post().uri("/movememts").body(Mono.just(model), MovementsMapper.class).retrieve().bodyToMono(Object.class).subscribe();
    }
    if (verifyNumberCC(number)) {
      Webclient.currentAccount.post().uri("/movememts").body(Mono.just(model), MovementsMapper.class).retrieve().bodyToMono(Object.class).subscribe();
    }
    if (verifyNumberFC(number)) {
      Webclient.fixedAccount.post().uri("/movememts").body(Mono.just(model), MovementsMapper.class).retrieve().bodyToMono(Object.class).subscribe();
    }
    card.getMovements().add(new MovementFrom(numberCard, number, amount));
    repository.save(card);
  }

  public Mono<Object> save(DebitCard model) {
    try {
      if (!verifyCustomer(model.getIdCustomer())) {
        return Mono.just(new Message(Constants.Messages.CLIENT_NOT_FOUND));
      }

      repository.save(model);
      return Mono.just(new Message(Constants.Messages.CLIENT_SUCCESS));
    } catch (Exception e) {
      return Mono.just(new Message(Constants.Messages.INVALID_DATA));
    }
  }

  public Mono<Object> setPrincipalAccount(String id, AccountAffiliate model) {
    if (verifyDebitCard(id, model)) {
      return Mono.just(new Message(Constants.Messages.INCORRECT_DATA));
    }

    if (!findAccountNumber(id, model.getNumberAccount())) {
      return Mono.just(new Message(Constants.Messages.AFILIATE_NOT_ACCOUNT));
    }

    DebitCard card = repository.findById(id).get();

    card
      .getAccounts() 
      .stream()
      .map(
        c -> { 
          c.setPrincipal(c.getNumberAccount().equals(model.getNumberAccount()));
          return c;
        }
      )
      .collect(Collectors.toList());

    repository.save(card);

    return Mono.just(new Message(Constants.Messages.UPDATE_TARGET));
  }

  public Mono<Object> addAccount(String id, AccountAffiliate model) {
    DebitCard card = repository.findById(id).get();

    if (verifyDebitCard(id, model)) {
      return Mono.just(new Message(Constants.Messages.INCORRECT_DATA));
    }

    if (findAccountNumber(id, model.getNumberAccount())) {
      return Mono.just(new Message(Constants.Messages.NOT_UPDATE_TARGET));
    }

    if (repository.findById(id).get().getAccounts().size() == 0) {
      if (model.getPrincipal()) {
        card.getAccounts().add(model);
      } else {
        return Mono.just(new Message(Constants.Messages.WARNING_TARGET));
      }
    } else {
      if (!model.getPrincipal()) {
        card.getAccounts().add(model);
      } else {
        return Mono.just(new Message(Constants.Messages.WARNING_TARGET_PRINCIPAL));
      }
    }

    repository.save(card);
    return Mono.just(new Message(Constants.Messages.SUCCESS_PROCESS_ACCOUNT));
  }

  public Mono<Object> addMovement(MovementFrom model) {
    try {
      DebitCard debitcard = repository.findAll().stream().filter(c -> c.getCardNumber().equals(model.getNumberDebitCard())).findFirst().get();
      String id = debitcard.getIdDebitCard();
      Double amount = model.getAmount();

      AccountMapper accountPrincipal = getAccountPrincipal(id).block();

      if (authCard(model) == null) return Mono.just(new Message(Constants.Messages.INCORRECT_DATA));

      if (accountPrincipal.getAmount() < amount) {
        double amountAll = getAllAmount(model.getNumberDebitCard());

        if (amountAll < amount) {
          return Mono.just(new Message(Constants.Messages.LIMIT_EXCEED_ACCOUNT));
        } else {
          double[] amountF = { model.getAmount() };
          AddMovementAccount(accountPrincipal.getAccountNumber(), accountPrincipal.getAmount(), model.getNumberDebitCard());
          amountF[0] = amountF[0] - accountPrincipal.getAmount();
          debitcard
            .getAccounts()
            .stream()
            .filter(c -> !c.getPrincipal())
            .collect(Collectors.toList())
            .forEach(
              c -> {
                double amountTemp = findByAccount(c.getNumberAccount()).getAmount();

                if ((amountTemp < amountF[0]) && (amountTemp != 0)) {
                  amountF[0] = amountF[0] - amountTemp;
                  AddMovementAccount(c.getNumberAccount(), amountTemp, model.getNumberDebitCard());
                }
                if (amountTemp > amountF[0]) {
                  AddMovementAccount(c.getNumberAccount(), amountF[0], model.getNumberDebitCard());
                  return;
                }
              }
            );
        }
        log.debug("GAAAAAAAAAA");
        return Mono.just(new Message(Constants.Messages.LIMIT_EXCEED_ACCOUNT_PRINCIPAL));
      }

      log.debug("HEY");
      AddMovementAccount(accountPrincipal.getAccountNumber(), model.getAmount(), model.getNumberDebitCard());
      return Mono.just(new Message(Constants.Messages.SUCCESS_OPERATION));
    } catch (Exception ex) {
      return Mono.just(new Message(Constants.Messages.NO_ACCOUNT));
    }
  }

  public Mono<Object> get(String number) {
    try {
      return Mono.just(repository.findAll().stream().filter(c -> c.getCardNumber().equals(number)).findAny().get());
    } catch (Exception e) {
      return Mono.just(new Message(Constants.Messages.TARGET_NOT_FOUND));
    }
  }

  public Flux<Object> getAll() {
    return Flux.fromIterable(repository.findAll());
  }

  public Flux<Object> getFindByCustomer(String id) {
    return Flux.fromIterable(repository.findAll().stream().filter(c -> c.getIdCustomer().equals(id)).collect(Collectors.toList()));
  }

  public Mono<AccountMapper> getAccountPrincipal(String id) {
    return Mono.just(
      findByAccount(repository.findById(id).get().getAccounts().stream().filter(c -> c.getPrincipal()).findAny().get().getNumberAccount())
    );
  }
}
