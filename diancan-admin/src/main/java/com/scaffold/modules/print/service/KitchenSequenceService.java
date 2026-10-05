package com.scaffold.modules.print.service;
import com.scaffold.modules.print.mapper.KitchenSequenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
@Service @RequiredArgsConstructor public class KitchenSequenceService {
 private final KitchenSequenceMapper mapper;
 public record Sequence(LocalDate date,int number) {}
 @Transactional(propagation=Propagation.MANDATORY) public Sequence next() {
  // One persistent sequence is also safe across midnight. Allocate only after business/stock writes.
  LocalDate key=LocalDate.of(1970,1,1);mapper.ensure(key);
  int next=Math.addExact(Math.max(mapper.lockedValue(key),mapper.largestAllocated()),1);mapper.setValue(key,next);
  return new Sequence(LocalDate.now(ZoneId.of("Asia/Shanghai")),next);
 }
}
