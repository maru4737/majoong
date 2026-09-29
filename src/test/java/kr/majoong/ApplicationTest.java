package kr.majoong;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "app.mode=demo",
    "spring.datasource.url=jdbc:h2:mem:majoong-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.data.redis.host=127.0.0.1",
    "spring.data.redis.port=1"
})
@AutoConfigureMockMvc
class ApplicationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;

  @Test void sourceStatusPublishesServerRangeAndDirections() throws Exception {
    mvc.perform(get("/api/v1/source-status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.airport").value("ICN"))
        .andExpect(jsonPath("$.directions[0]").value("ARRIVAL"))
        .andExpect(jsonPath("$.searchDateFrom").exists())
        .andExpect(jsonPath("$.searchDateTo").exists());
  }

  @Test void flightSearchRejectsInvalidDirectionWithStructuredError() throws Exception {
    mvc.perform(get("/api/v1/flights").param("date", "today").param("direction", "SIDEWAYS"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test void flightSearchRejectsOversizedQueryBeforeCallingSource() throws Exception {
    mvc.perform(get("/api/v1/flights").param("date", "today").param("q", "x".repeat(41)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @Test void tripCanBeCreatedReadAndUpdatedWithRevision() throws Exception {
    String create = """
        {"type":"ROUND_TRIP","name":"도쿄 여행","segments":[
          {"date":"2030-01-10","origin":"ICN","destination":"NRT","flightNumber":"KE703"},
          {"date":"2030-01-14","origin":"NRT","destination":"ICN","flightNumber":"KE704"}
        ]}
        """;
    MvcResult created = mvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON).content(create))
        .andExpect(status().isCreated())
        .andExpect(cookie().exists("majoong_owner"))
        .andExpect(jsonPath("$.revision").value(1))
        .andExpect(jsonPath("$.state").value("MANUAL_UNVERIFIED"))
        .andReturn();
    Cookie owner = created.getResponse().getCookie("majoong_owner");
    String body = created.getResponse().getContentAsString();
    String id = body.substring(body.indexOf("\"id\":\"") + 6, body.indexOf('"', body.indexOf("\"id\":\"") + 6));

    mvc.perform(get("/api/v1/trips/" + id).cookie(owner))
        .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("도쿄 여행"));

    String update = create.replace("\"name\":\"도쿄 여행\"", "\"name\":\"가족 도쿄 여행\",\"expectedRevision\":1");
    mvc.perform(patch("/api/v1/trips/" + id).cookie(owner).contentType(MediaType.APPLICATION_JSON).content(update))
        .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.name").value("가족 도쿄 여행"));

    mvc.perform(patch("/api/v1/trips/" + id).cookie(owner).contentType(MediaType.APPLICATION_JSON).content(update))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.message", containsString("다시 불러와")));

    mvc.perform(delete("/api/v1/trips/" + id).cookie(owner))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/trips").param("state", "trash").cookie(owner))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
    mvc.perform(post("/api/v1/trips/" + id + "/restore").cookie(owner))
        .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
  }

  @Test void roundTripValidationRejectsMismatchedReturnRoute() throws Exception {
    String body = """
        {"type":"ROUND_TRIP","name":"잘못된 왕복","segments":[
          {"date":"2030-01-10","origin":"ICN","destination":"NRT"},
          {"date":"2030-01-14","origin":"KIX","destination":"ICN"}
        ]}
        """;
    mvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("왕복의 출발·도착 공항이 서로 맞지 않아요."));
  }

  @Test void clientSuppliedProviderIdDoesNotMakeUnverifiedTripOfficial() throws Exception {
    String body = """
        {"type":"ONE_WAY","name":"검증 없는 연결","segments":[
          {"date":"2030-01-10","origin":"ICN","destination":"NRT","providerId":"forged","direction":"DEPARTURE","sourceState":"OFFICIAL_LINKED"}
        ]}
        """;
    mvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("MANUAL_UNVERIFIED"))
        .andExpect(jsonPath("$.segments[0].providerId").value(""))
        .andExpect(jsonPath("$.segments[0].sourceState").value("MANUAL_UNVERIFIED"));
  }

  @Test void completedMeetupRejectsLaterStatusAndPointWrites() throws Exception {
    String rawOwner="test-owner",ownerHash=sha256(rawOwner),id="00000000-0000-4000-8000-000000000111";Instant now=Instant.now();
    db.update("INSERT INTO meetups (id,owner_hash,revision,flight,origin,destination,scheduled,estimated,scheduled_date,terminal,arrival_exit,baggage,traveler_status,note,point_terminal,point_area,point_exit,point_landmark,completed,updated_at,provider_id,direction,source_observed_at,traveler_updated_at,meeting_updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,ownerHash,1,"TEST1","HKG","ICN","10:00","10:00","2030-01-10","제1여객터미널","","","NOT_SHARED","","","","","",false,now,"","ARRIVAL",now,now,now);
    Cookie owner=new Cookie("majoong_owner",rawOwner);
    mvc.perform(post("/api/v1/meetups/"+id+"/complete").cookie(owner).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
    mvc.perform(patch("/api/v1/meetups/"+id+"/traveler-status").cookie(owner).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OUTSIDE\",\"expectedVersion\":2}"))
        .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("SHARE_ENDED"));
    mvc.perform(patch("/api/v1/meetups/"+id+"/meeting-point").cookie(owner).contentType(MediaType.APPLICATION_JSON).content("{\"terminal\":\"\",\"publicArea\":\"\",\"exitLabel\":\"\",\"landmark\":\"\",\"note\":\"\",\"expectedVersion\":2}"))
        .andExpect(status().isGone());
  }

  private static String sha256(String value) throws Exception {return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
}
