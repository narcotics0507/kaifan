package com.scaffold.modules.print.service;
import com.scaffold.modules.print.mapper.KitchenSequenceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
@Service public class KitchenSequenceService {
 private final KitchenSequenceMapper mapper;
 private final Clock clock;
 @Autowired public KitchenSequenceService(KitchenSequenceMapper mapper) {this(mapper,Clock.system(ZoneId.of("Asia/Shanghai")));}
 public KitchenSequenceService(KitchenSequenceMapper mapper,Clock clock) {this.mapper=mapper;this.clock=clock;}
 public record Sequence(LocalDate date,int number) {}
 @Transactional(propagation=Propagation.MANDATORY) public Sequence next() {
  // A fresh Shanghai calendar day starts at 001; existing tickets retain their date and number.
  LocalDate key=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));mapper.ensure(key);
  int next=Math.addExact(Math.max(mapper.lockedValue(key),mapper.largestAllocated(key)),1);mapper.setValue(key,next);
  return new Sequence(key,next);
 }
}
