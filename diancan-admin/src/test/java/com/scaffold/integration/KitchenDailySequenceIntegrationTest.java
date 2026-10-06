package com.scaffold.integration;

import com.scaffold.DiancanAdminApplication;
import com.scaffold.framework.websocket.WsService;
import com.scaffold.modules.print.mapper.KitchenSequenceMapper;
import com.scaffold.modules.print.service.KitchenSequenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = DiancanAdminApplication.class)
@ActiveProfiles("test")
class KitchenDailySequenceIntegrationTest {
    @Autowired KitchenSequenceMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockBean WsService websocket;
    @BeforeEach @AfterEach void clearOwnedSequenceFixtures() {
        jdbc.update("DELETE FROM kitchen_sequence WHERE sequence_date BETWEEN '2035-06-01' AND '2035-06-06' OR sequence_date='1970-01-01'");
        jdbc.update("DELETE FROM order_operation_log WHERE id=7350603001 AND reason='daily-sequence-test'");
    }

    private KitchenSequenceService at(String instant) {
        return new KitchenSequenceService(mapper, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
    private KitchenSequenceService.Sequence next(KitchenSequenceService service) {
        return new TransactionTemplate(transactions).execute(status -> service.next());
    }

    @Test void midnightInShanghaiStartsNewDayAtOne() {
        var before = at("2035-06-01T15:59:59Z");
        var after = at("2035-06-01T16:00:00Z");
        assertEquals(LocalDate.of(2035,6,1), next(before).date());
        assertEquals(2, next(before).number());
        var first = next(after);
        assertEquals(LocalDate.of(2035,6,2), first.date());
        assertEquals(1, first.number());
        assertEquals(2, next(after).number());
    }

    @Test void keepsExistingDailyTicketsAndIgnoresOldGlobalCounter() {
        mapper.ensure(LocalDate.of(1970,1,1)); mapper.setValue(LocalDate.of(1970,1,1),9000);
        jdbc.update("INSERT INTO order_operation_log(id,order_id,operation_type,operator_id,reason,detail) VALUES(?,?,?,?,?,?)",
                7350603001L,0L,"TICKET_ADD",0L,"daily-sequence-test","{\"queueDate\":\"2035-06-03\",\"queueNumber\":77,\"text\":\"old ticket\"}");
        var number=next(at("2035-06-03T02:00:00Z"));
        assertEquals(78,number.number());
        assertTrue(jdbc.queryForObject("SELECT detail FROM order_operation_log WHERE id=7350603001",String.class).contains("\"queueNumber\":77"));
        assertEquals(79,next(at("2035-06-03T03:00:00Z")).number());
        assertEquals(1,next(at("2035-06-04T02:00:00Z")).number());
    }

    @Test void failedTransactionDoesNotConsumeDailyNumber() {
        var service=at("2035-06-05T02:00:00Z");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{service.next();throw new IllegalStateException("rollback");}));
        assertEquals(1,next(service).number());
    }

    @Test void concurrentDailyNumbersStayUniqueAndPersistAcrossServiceRestart() throws Exception {
        var service=at("2035-06-06T02:00:00Z");
        var pool=Executors.newFixedThreadPool(6);
        try {
            var tasks=new ArrayList<Callable<Integer>>();
            for(int i=0;i<12;i++)tasks.add(()->next(service).number());
            Set<Integer> numbers=new TreeSet<>();
            for(var result:pool.invokeAll(tasks))numbers.add(result.get(20,TimeUnit.SECONDS));
            assertEquals(12,numbers.size());assertEquals(1,Collections.min(numbers));assertEquals(12,Collections.max(numbers));
            assertEquals(13,next(at("2035-06-06T03:00:00Z")).number());
        } finally {pool.shutdownNow();}
    }
}
