package org.ibm.michele;

import jakarta.ws.rs.Produces;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import io.agroal.api.AgroalDataSource;
import jakarta.ws.rs.core.MediaType;

@ApplicationScoped              

public class GetPGSQLtime {
   @Inject
   AgroalDataSource ds;
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getTime() {
        String toReturn=null;
        try (Connection con = ds.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT CURRENT_TIMESTAMP");) {
            try (ResultSet rs = ps.executeQuery();) {
                rs.next();
                toReturn = "Current timestamp: " + rs.getString(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return toReturn;
    }    
}
