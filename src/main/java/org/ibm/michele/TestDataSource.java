package org.ibm.michele;

import jakarta.ws.rs.Produces;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;


@Path("/test")
public class TestDataSource {
    @Inject
    GetPGSQLtime getPGSQLtime;            


    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return getPGSQLtime.getTime();
    }
}
