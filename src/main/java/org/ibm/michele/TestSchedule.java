package org.ibm.michele;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.ScheduledExecution;

@ApplicationScoped              
public class TestSchedule {
    @Inject
    GetPGSQLtime getPGSQLtime;  

    @Scheduled(every="10s")     
    void getTime() {
        System.out.println("every 10s: " + getPGSQLtime.getTime()); 
    }

    @Scheduled(cron="0 15 10 * * ?") 
    void cronJob(ScheduledExecution execution) {
        getPGSQLtime.getTime(); 
        System.out.println(execution.getScheduledFireTime()+ getPGSQLtime.getTime());
    }

    @Scheduled(cron = "{cron.expr}") 
    void cronJobWithExpressionInConfig() {
        getPGSQLtime.getTime(); 
       System.out.println("Cron expression configured in application.properties: " +  getPGSQLtime.getTime());
    }
}