package kr.majoong;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.*;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.WebUtils;

@RestController
@RequestMapping("/api/v1")
public class Application {
  private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
  private static final String OWNER_COOKIE="majoong_owner";
  private static final Logger LOG=LoggerFactory.getLogger(Application.class);
  private static final Set<String> DIRECTIONS=Set.of("ARRIVAL","DEPARTURE");
  private static final Set<String> TRIP_TYPES=Set.of("ONE_WAY","ROUND_TRIP","MULTI_CITY");
  private static final Set<String> TRAVELER_STATES=Set.of("NOT_SHARED","IMMIGRATION","WAITING_BAGGAGE","OUTSIDE","MET");
  private final String mode,icnServiceKey;
  private final RestClient http=RestClient.create();
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;
  private final SecureRandom random=new SecureRandom();
  private final Object writeLock=new Object();
  private final Map<String,Object> collectionLocks=new ConcurrentHashMap<>();
  private final Counter upstreamCalls,upstreamFailures,cacheHits,tripWrites;

  public Application(@Value("${app.mode:demo}") String mode,@Value("${ICN_SERVICE_KEY:}") String icnServiceKey,
      JdbcTemplate db,ObjectMapper json,StringRedisTemplate redis,MeterRegistry registry){
    this.mode=mode;this.icnServiceKey=icnServiceKey;this.db=db;this.json=json;this.redis=redis;
    upstreamCalls=registry.counter("majoong_upstream_calls_total");
    upstreamFailures=registry.counter("majoong_upstream_failures_total");
    cacheHits=registry.counter("majoong_flight_cache_hits_total");
    tripWrites=registry.counter("majoong_trip_writes_total");
  }

  @PostConstruct void schema(){
    db.execute("CREATE TABLE IF NOT EXISTS meetups (id VARCHAR(36) PRIMARY KEY, owner_hash VARCHAR(64) NOT NULL, revision BIGINT NOT NULL, flight VARCHAR(32) NOT NULL, origin VARCHAR(80) NOT NULL, destination VARCHAR(80) NOT NULL, scheduled VARCHAR(16) NOT NULL, estimated VARCHAR(16) NOT NULL, scheduled_date VARCHAR(10) NOT NULL, terminal VARCHAR(160) NOT NULL, arrival_exit VARCHAR(40) NOT NULL, baggage VARCHAR(40) NOT NULL, traveler_status VARCHAR(40) NOT NULL, note VARCHAR(300) NOT NULL, point_terminal VARCHAR(160) NOT NULL, point_area VARCHAR(120) NOT NULL, point_exit VARCHAR(40) NOT NULL, point_landmark VARCHAR(160) NOT NULL, completed BOOLEAN NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
    db.execute("ALTER TABLE meetups ADD COLUMN IF NOT EXISTS provider_id VARCHAR(160) DEFAULT '' NOT NULL");
    db.execute("ALTER TABLE meetups ADD COLUMN IF NOT EXISTS direction VARCHAR(16) DEFAULT 'ARRIVAL' NOT NULL");
    db.execute("ALTER TABLE meetups ADD COLUMN IF NOT EXISTS source_observed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL");
    db.execute("ALTER TABLE meetups ADD COLUMN IF NOT EXISTS traveler_updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL");
    db.execute("ALTER TABLE meetups ADD COLUMN IF NOT EXISTS meeting_updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL");
    db.execute("CREATE TABLE IF NOT EXISTS shares (token_hash VARCHAR(64) PRIMARY KEY, meetup_id VARCHAR(36) NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked BOOLEAN NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL)");
    db.execute("CREATE TABLE IF NOT EXISTS trips (id VARCHAR(36) PRIMARY KEY, owner_hash VARCHAR(64) NOT NULL, trip_type VARCHAR(20) NOT NULL, name VARCHAR(120) NOT NULL, segments_json CLOB NOT NULL, state VARCHAR(40) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL)");
    db.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS revision BIGINT DEFAULT 1 NOT NULL");
    db.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL");
    db.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS deleted BOOLEAN DEFAULT FALSE NOT NULL");
    db.execute("CREATE TABLE IF NOT EXISTS watches (id VARCHAR(36) PRIMARY KEY, owner_hash VARCHAR(64) NOT NULL, provider_id VARCHAR(160) NOT NULL, flight VARCHAR(32) NOT NULL, direction VARCHAR(16) NOT NULL, scheduled_date VARCHAR(10) NOT NULL, snapshot_json CLOB NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(owner_hash,provider_id,direction,scheduled_date))");
    db.execute("CREATE TABLE IF NOT EXISTS notifications (id VARCHAR(36) PRIMARY KEY, owner_hash VARCHAR(64) NOT NULL, watch_id VARCHAR(36) NOT NULL, change_key VARCHAR(240) NOT NULL, title VARCHAR(160) NOT NULL, message VARCHAR(500) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, read_at TIMESTAMP WITH TIME ZONE, UNIQUE(watch_id,change_key))");
  }

  private boolean live(){return mode.equalsIgnoreCase("live");}

  @GetMapping("/flights") public Map<String,Object> flights(
      @RequestParam(required=false) String date,@RequestParam(required=false) String arrivalDate,
      @RequestParam(required=false) String q,@RequestParam(required=false) String flightNumber,
      @RequestParam(defaultValue="ARRIVAL") String direction){
    String requestedDate=firstNonBlank(date,arrivalDate,"today"),query=firstNonBlank(q,flightNumber,"");
    String normalizedDirection=normalizedDirection(direction);
    LocalDate today=LocalDate.now(SEOUL),requested;
    try{requested="today".equalsIgnoreCase(requestedDate)?today:LocalDate.parse(requestedDate);}
    catch(Exception e){throw new DateOutOfRange("날짜는 YYYY-MM-DD 형식이어야 해요.");}
    if(requested.isBefore(today.minusDays(3))||requested.isAfter(today.plusDays(6)))
      throw new DateOutOfRange("조회 가능한 날짜는 "+today.minusDays(3)+"부터 "+today.plusDays(6)+"까지예요.");
    if(live())return liveFlights(requested.toString(),query,normalizedDirection);
    return Map.of("query",Map.of("airport","ICN","direction",normalizedDirection,"sourceLocalDate",requested.toString(),"zoneId","Asia/Seoul"),"coverage",Map.of("state","DEMO"),"items",List.of(),"page",Map.of("returnedCount",0));
  }

  private Map<String,Object> liveFlights(String date,String query,String direction){
    if(icnServiceKey.isBlank())throw new SourceUnavailable("공항 데이터 인증이 설정되지 않았어요.");
    String cacheKey="majoong:flights:"+date+":"+direction;
    Map<String,Object> base=readCache(cacheKey);
    if(base!=null){cacheHits.increment();observeWatches(date,direction,base);return filter(base,query);}
    Object lock=collectionLocks.computeIfAbsent(cacheKey,key->new Object());
    synchronized(lock){
      try{
        base=readCache(cacheKey);
        if(base==null){base=collectFlights(date,"DEPARTURE".equals(direction));writeCache(cacheKey,base);}else cacheHits.increment();
      }finally{collectionLocks.remove(cacheKey,lock);}
    }
    observeWatches(date,direction,base);
    return filter(base,query);
  }

  private Map<String,Object> collectFlights(String date,boolean departure){
    String direction=departure?"DEPARTURE":"ARRIVAL";
    try{
      JsonNodeResult result=fetchAll(date,departure);List<Map<String,Object>> items=new ArrayList<>();Instant observed=Instant.now();
      for(FlightRow row:result.rows){
        if(!row.schedule.startsWith(date.replace("-","")))continue;
        Map<String,Object> item=new LinkedHashMap<>();
        item.put("id",row.id);item.put("flightKey","ICN_DETAIL|"+row.id+"|"+direction+"|"+date);
        item.put("displayNumber",blankAs(row.number,"편명 미발표"));
        item.put("origin",departure?"ICN":blankAs(row.airportCode,"미발표"));item.put("originName",departure?"인천국제공항":blankAs(row.airportName,"공항명 미발표"));
        item.put("destination",departure?blankAs(row.airportCode,"미발표"):"ICN");item.put("destinationName",departure?blankAs(row.airportName,"공항명 미발표"):"인천국제공항");
        item.put("scheduledLocalTime",time(row.schedule));item.put("estimatedLocalTime",time(row.estimated));item.put("scheduledDate",date);item.put("sourceLocalDate",date);item.put("zoneId","Asia/Seoul");
        item.put("terminalRawCode",blankAs(row.terminal,"미발표"));item.put("terminalDisplayName",terminalDisplay(row.terminal));
        item.put("arrivalExit",blankAs(row.exit,"미발표"));item.put("baggageCarousel",blankAs(row.baggage,"미발표"));item.put("status",blankAs(row.status,"상태 미발표"));
        item.put("dataMode","LIVE");item.put("observedAt",observed.toString());item.put("direction",direction);item.put("source","ICN_DETAIL");items.add(item);
      }
      boolean partial=result.partial;Map<String,Object> response=new LinkedHashMap<>();
      response.put("query",Map.of("airport","ICN","direction",direction,"sourceLocalDate",date,"zoneId","Asia/Seoul"));response.put("items",items);
      response.put("page",Map.of("returnedCount",items.size(),"upstreamTotal",result.total,"fetchedPages",result.pages,"nextPageAvailable",partial));
      response.put("coverage",Map.of("state",partial?"PARTIAL":"COMPLETE","reasonCode",partial?"SOURCE_PAGE_FETCH_INCOMPLETE":"SOURCE_COMPLETE","coveredWindows",List.of("00:00-24:00")));
      response.put("source",Map.of("dataMode","LIVE","lastSuccessfulCollectionAt",observed.toString(),"freshness","FRESH","cache","REDIS_900_SECONDS"));
      response.put("sourceNote",partial?"일부 페이지를 가져오지 못했습니다. 잠시 후 다시 조회해 주세요.":"인천공항 상세 운항 API의 예정시각 기준 전체 조회 결과입니다.");
      LOG.info("flight_lookup date={} direction={} items={} pages={} partial={}",date,direction,items.size(),result.pages,partial);return response;
    }catch(SourceUnavailable e){throw e;}catch(Exception e){upstreamFailures.increment();LOG.warn("flight_lookup_failed date={} direction={}",date,direction,e);throw new SourceUnavailable("공항 정보 갱신이 지연되고 있어요.");}
  }

  private JsonNodeResult fetchAll(String date,boolean departure){
    JsonNodeResult first=fetch(date,departure,1);int expected=Math.max(1,(int)Math.ceil(Math.max(0,first.total)/100.0));
    if(expected>20)throw new SourceUnavailable("공항 데이터가 예상 범위를 초과했어요. 잠시 후 다시 조회해 주세요.");
    List<FlightRow> all=new ArrayList<>(first.rows);boolean partial=false;int completed=1;
    for(int page=2;page<=expected;page++){try{all.addAll(fetch(date,departure,page).rows);completed=page;}catch(SourceUnavailable e){partial=true;break;}}
    return new JsonNodeResult(first.total,all,completed,partial);
  }

  private JsonNodeResult fetch(String date,boolean departure,int pageNo){
    upstreamCalls.increment();
    try{
      var root=http.get().uri(builder->builder.scheme("https").host("apis.data.go.kr").path(departure?"/B551177/statusOfAllFltDeOdp/getFltDeparturesDeOdp":"/B551177/statusOfAllFltDeOdp/getFltArrivalsDeOdp")
          .queryParam("serviceKey",icnServiceKey).queryParam("type","json").queryParam("searchdtCode","S").queryParam("searchDate",date.replace("-",""))
          .queryParam("searchFrom","0000").queryParam("searchTo","2400").queryParam("passengerOrCargo","P").queryParam("numOfRows",100).queryParam("pageNo",pageNo).build())
          .retrieve().body(com.fasterxml.jackson.databind.JsonNode.class);
      if(root==null||!"00".equals(root.path("response").path("header").path("resultCode").asText()))throw new SourceUnavailable("공항 데이터 원본이 응답하지 않았어요.");
      var body=root.path("response").path("body");List<FlightRow> rows=new ArrayList<>();
      for(var row:body.path("items"))rows.add(new FlightRow(row.path("fid").asText("unknown"),row.path("flightId").asText("").replace(" ",""),row.path("scheduleDatetime").asText(""),row.path("estimatedDatetime").asText(""),row.path("airportCode").asText(""),row.path("airport").asText(""),row.path("terminalId").asText(""),row.path("exitNumber").asText(""),row.path("carousel").asText(""),row.path("remark").asText("")));
      return new JsonNodeResult(body.path("totalCount").asInt(-1),rows,pageNo,false);
    }catch(SourceUnavailable e){upstreamFailures.increment();throw e;}catch(Exception e){upstreamFailures.increment();throw new SourceUnavailable("공항 데이터 원본이 응답하지 않았어요.");}
  }

  @SuppressWarnings("unchecked") private Map<String,Object> readCache(String key){try{String value=redis.opsForValue().get(key);return value==null?null:json.readValue(value,new TypeReference<Map<String,Object>>(){});}catch(Exception e){return null;}}
  private void writeCache(String key,Map<String,Object> value){try{redis.opsForValue().set(key,toJson(value),Duration.ofMinutes(15));}catch(Exception e){LOG.warn("flight_cache_write_failed key={}",key);}}
  @SuppressWarnings("unchecked") private Map<String,Object> filter(Map<String,Object> base,String query){
    if(query==null||query.isBlank())return base;String normalized=normalize(query);
    List<Map<String,Object>> items=((List<Map<String,Object>>)base.get("items")).stream().filter(item->matches(item,normalized)).toList();
    Map<String,Object> out=new LinkedHashMap<>(base);out.put("items",items);Map<String,Object> page=new LinkedHashMap<>((Map<String,Object>)base.get("page"));page.put("returnedCount",items.size());out.put("page",page);return out;
  }
  private boolean matches(Map<String,Object> item,String query){return List.of("displayNumber","origin","originName","destination","destinationName").stream().map(key->normalize(String.valueOf(item.get(key)))).anyMatch(value->value.contains(query));}

  @GetMapping("/source-status") public Map<String,Object> sourceStatus(){LocalDate today=LocalDate.now(SEOUL);return Map.of("enabled",live(),"mode",mode.toUpperCase(Locale.ROOT),"airport","ICN","directions",List.of("ARRIVAL","DEPARTURE"),"serverLocalDate",today.toString(),"searchDateFrom",today.minusDays(3).toString(),"searchDateTo",today.plusDays(6).toString(),"zoneId","Asia/Seoul","cache","REDIS_900_SECONDS");}
  @GetMapping("/airport-guides") public List<Map<String,Object>> airportGuides(){return List.of(
      guide("ICN","인천국제공항","제1·제2여객터미널과 탑승동","안내·환전·의료·교통약자 서비스","https://www.airport.kr/geomap/ap_ko/view.do?type=2&alertType=0&tmnlId=P01","https://www.airport.kr/ap_ko/1376/subview.do","인천국제공항공사"),
      guide("GMP","김포국제공항","국내선·국제선 층별 지도","식당·카페·라운지·수유실·AED","https://www.airport.co.kr/gimpo/cms/frFacCon/facilityMapList.do?MENU_ID=1420&acd=A1101&listGbn=2","https://www.airport.co.kr/gimpo/cms/frFacCon/facilityList.do?MENU_ID=1420&acd=A1101&listGbn=1","한국공항공사"),
      guide("CJU","제주국제공항","국내선·국제선 층별 지도","식당·카페·라운지·교통약자 시설·AED","https://www.airport.co.kr/jeju/cms/frFacCon/facilityMapList.do?MENU_ID=160&acd=A1103&listGbn=2","https://www.airport.co.kr/jeju/cms/frFacCon/facilityList.do?MENU_ID=160&acd=A1103&listGbn=1","한국공항공사"),
      guide("PUS","김해국제공항","국내선·국제선 층별 지도","식당·로밍·충전·의료·교통약자 시설","https://www.airport.co.kr/gimhae/cms/frFacCon/facilityMapList.do?MENU_ID=220&acd=A1102&listGbn=2","https://www.airport.co.kr/gimhae/cms/frFacCon/facilityList.do?MENU_ID=220&acd=A1102&listGbn=1","한국공항공사"));}

  @PostMapping("/meetups") @ResponseStatus(HttpStatus.CREATED)
  public Meetup createMeetup(@Valid @RequestBody CreateMeetupRequest body,HttpServletRequest request,HttpServletResponse response){
    String direction=normalizedDirection(body.direction());LocalDate.parse(body.scheduledDate());Map<String,Object> flight=verifiedFlight(body.providerId(),body.scheduledDate(),direction);
    String owner=owner(request,response),id=UUID.randomUUID().toString();Instant now=Instant.now();
    db.update("INSERT INTO meetups (id,owner_hash,revision,flight,origin,destination,scheduled,estimated,scheduled_date,terminal,arrival_exit,baggage,traveler_status,note,point_terminal,point_area,point_exit,point_landmark,completed,updated_at,provider_id,direction,source_observed_at,traveler_updated_at,meeting_updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,owner,1,text(flight,"displayNumber"),text(flight,"origin"),text(flight,"destination"),text(flight,"scheduledLocalTime"),text(flight,"estimatedLocalTime"),body.scheduledDate(),text(flight,"terminalDisplayName"),text(flight,"arrivalExit"),text(flight,"baggageCarousel"),"NOT_SHARED","","","","","",false,now,body.providerId(),direction,parseInstant(flight.get("observedAt"),now),now,now);
    return requireMeetup(id);
  }

  @GetMapping("/meetups") public List<Meetup> meetups(@RequestParam(defaultValue="active") String state,HttpServletRequest request,HttpServletResponse response){
    noStore(response);String owner=existingOwner(request);if(owner==null)return List.of();boolean completed="completed".equalsIgnoreCase(state);
    return db.query(meetupSelect()+" WHERE owner_hash=? AND completed=? ORDER BY updated_at DESC",this::mapMeetup,owner,completed);
  }
  @GetMapping("/meetups/{id}") public Meetup meetup(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){noStore(response);Meetup meetup=requireMeetup(id);own(meetup,request);refreshMeetupFromCache(meetup);return requireMeetup(id);}
  @PatchMapping("/meetups/{id}/traveler-status") public Meetup status(@PathVariable String id,@Valid @RequestBody StatusRequest body,HttpServletRequest request,HttpServletResponse response){
    noStore(response);Meetup meetup=requireMeetup(id);own(meetup,request);if(!TRAVELER_STATES.contains(body.status()))throw new Bad("여행자 상태가 올바르지 않아요.");Instant now=Instant.now();
    update(id,body.expectedVersion(),"UPDATE meetups SET traveler_status=?,traveler_updated_at=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",body.status(),now,now);return requireMeetup(id);
  }
  @PatchMapping("/meetups/{id}/meeting-note") public Meetup note(@PathVariable String id,@Valid @RequestBody NoteRequest body,HttpServletRequest request,HttpServletResponse response){
    noStore(response);Meetup meetup=requireMeetup(id);own(meetup,request);Instant now=Instant.now();update(id,body.expectedVersion(),"UPDATE meetups SET note=?,meeting_updated_at=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",body.text(),now,now);return requireMeetup(id);
  }
  @PatchMapping("/meetups/{id}/meeting-point") public Meetup point(@PathVariable String id,@Valid @RequestBody MeetingPointRequest body,HttpServletRequest request,HttpServletResponse response){
    noStore(response);Meetup meetup=requireMeetup(id);own(meetup,request);Instant now=Instant.now();update(id,body.expectedVersion(),"UPDATE meetups SET point_terminal=?,point_area=?,point_exit=?,point_landmark=?,note=?,meeting_updated_at=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",body.terminal(),body.publicArea(),body.exitLabel(),body.landmark(),body.note(),now,now);return requireMeetup(id);
  }
  @PostMapping("/meetups/{id}/complete") public Meetup complete(@PathVariable String id,@Valid @RequestBody CompleteRequest body,HttpServletRequest request,HttpServletResponse response){
    noStore(response);Meetup meetup=requireMeetup(id);own(meetup,request);synchronized(writeLock){Instant now=Instant.now();update(id,body.expectedVersion(),"UPDATE meetups SET traveler_status='MET',completed=TRUE,traveler_updated_at=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",now,now);db.update("UPDATE shares SET revoked=TRUE WHERE meetup_id=?",id);}return requireMeetup(id);
  }
  @PostMapping("/meetups/{id}/shares") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> share(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){
    noStore(response);Meetup meetup=requireMeetup(id);own(meetup,request);if(meetup.completed)throw new Gone("완료된 마중방이에요.");byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);Instant expiry=Instant.now().plus(Duration.ofHours(24));db.update("INSERT INTO shares VALUES (?,?,?,?,?)",hash(token),id,expiry,false,Instant.now());return Map.of("url","/majoong/s#token="+token,"expiresAt",expiry.toString(),"scope","항공편·공식 공항 정보·여행자가 입력한 상태와 만남 장소");
  }
  @PostMapping("/shares/exchange") public Map<String,Object> exchange(@Valid @RequestBody TokenRequest body,HttpServletResponse response){
    noStore(response);try{Share share=db.queryForObject("SELECT token_hash,meetup_id,expires_at,revoked FROM shares WHERE token_hash=?",(rs,row)->new Share(rs.getString(1),rs.getString(2),rs.getObject(3,Instant.class),rs.getBoolean(4)),hash(body.token()));Meetup meetup=requireMeetup(share.meetup);if(share.revoked||share.expiry.isBefore(Instant.now())||meetup.completed)throw new Gone("공유가 종료됐어요.");refreshMeetupFromCache(meetup);return shared(requireMeetup(share.meetup));}catch(EmptyResultDataAccessException e){throw new Gone("공유가 종료됐어요.");}
  }

  @PostMapping("/trips") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createTrip(@Valid @RequestBody TripRequest body,HttpServletRequest request,HttpServletResponse response){
    String owner=owner(request,response);TripRequest normalized=normalizeTrip(body);validateTrip(normalized);String id=UUID.randomUUID().toString();Instant now=Instant.now();
    db.update("INSERT INTO trips (id,owner_hash,trip_type,name,segments_json,state,created_at,revision,updated_at,deleted) VALUES (?,?,?,?,?,?,?,?,?,FALSE)",id,owner,normalized.type(),normalized.name(),toJson(normalized.segments()),tripState(normalized.segments()),now,1,now);tripWrites.increment();return requireTrip(id,owner);
  }
  @GetMapping("/trips") public List<Map<String,Object>> trips(HttpServletRequest request,HttpServletResponse response){
    noStore(response);String owner=existingOwner(request);if(owner==null)return List.of();return db.query("SELECT id,trip_type,name,segments_json,state,created_at,revision,updated_at FROM trips WHERE owner_hash=? AND deleted=FALSE ORDER BY updated_at DESC",(rs,row)->tripMap(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,Instant.class),rs.getLong(7),rs.getObject(8,Instant.class)),owner);
  }
  @GetMapping("/trips/{id}") public Map<String,Object> trip(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){noStore(response);String owner=existingOwner(request);if(owner==null)throw new Missing("여정을 찾을 수 없어요.");return requireTrip(id,owner);}
  @PatchMapping("/trips/{id}") public Map<String,Object> updateTrip(@PathVariable String id,@Valid @RequestBody TripRequest body,HttpServletRequest request,HttpServletResponse response){
    noStore(response);String owner=existingOwner(request);if(owner==null)throw new Missing("여정을 찾을 수 없어요.");TripRequest normalized=normalizeTrip(body);validateTrip(normalized);if(body.expectedRevision()==null)throw new Bad("수정 버전이 필요해요.");
    int changed=db.update("UPDATE trips SET trip_type=?,name=?,segments_json=?,state=?,revision=revision+1,updated_at=? WHERE id=? AND owner_hash=? AND revision=? AND deleted=FALSE",normalized.type(),normalized.name(),toJson(normalized.segments()),tripState(normalized.segments()),Instant.now(),id,owner,body.expectedRevision());
    if(changed!=1)throw new Conflict("다른 화면에서 여정이 수정됐어요. 다시 불러와 주세요.");tripWrites.increment();return requireTrip(id,owner);
  }
  @DeleteMapping("/trips/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteTrip(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){noStore(response);String owner=existingOwner(request);if(owner==null||db.update("UPDATE trips SET deleted=TRUE,updated_at=? WHERE id=? AND owner_hash=? AND deleted=FALSE",Instant.now(),id,owner)!=1)throw new Missing("여정을 찾을 수 없어요.");}

  @PostMapping("/watches") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createWatch(@Valid @RequestBody WatchRequest body,HttpServletRequest request,HttpServletResponse response){
    String direction=normalizedDirection(body.direction());Map<String,Object> flight=verifiedFlight(body.providerId(),body.scheduledDate(),direction);String owner=owner(request,response),id=UUID.randomUUID().toString();Instant now=Instant.now();
    try{db.update("INSERT INTO watches VALUES (?,?,?,?,?,?,?,?)",id,owner,body.providerId(),text(flight,"displayNumber"),direction,body.scheduledDate(),toJson(snapshot(flight)),now);}catch(Exception e){return db.queryForObject("SELECT id,flight,direction,scheduled_date,created_at FROM watches WHERE owner_hash=? AND provider_id=? AND direction=? AND scheduled_date=?",(rs,row)->Map.of("id",rs.getString(1),"flight",rs.getString(2),"direction",rs.getString(3),"scheduledDate",rs.getString(4),"createdAt",rs.getObject(5,Instant.class).toString()),owner,body.providerId(),direction,body.scheduledDate());}
    return Map.of("id",id,"flight",text(flight,"displayNumber"),"direction",direction,"scheduledDate",body.scheduledDate(),"createdAt",now.toString());
  }
  @GetMapping("/watches") public List<Map<String,Object>> watches(HttpServletRequest request,HttpServletResponse response){noStore(response);String owner=existingOwner(request);if(owner==null)return List.of();return db.query("SELECT id,provider_id,flight,direction,scheduled_date,created_at FROM watches WHERE owner_hash=? ORDER BY created_at DESC",(rs,row)->Map.of("id",rs.getString(1),"providerId",rs.getString(2),"flight",rs.getString(3),"direction",rs.getString(4),"scheduledDate",rs.getString(5),"createdAt",rs.getObject(6,Instant.class).toString()),owner);}
  @DeleteMapping("/watches/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteWatch(@PathVariable String id,HttpServletRequest request){String owner=existingOwner(request);if(owner==null||db.update("DELETE FROM watches WHERE id=? AND owner_hash=?",id,owner)!=1)throw new Missing("관심 항공편을 찾을 수 없어요.");}
  @GetMapping("/notifications") public List<Map<String,Object>> notifications(HttpServletRequest request,HttpServletResponse response){noStore(response);String owner=existingOwner(request);if(owner==null)return List.of();return db.query("SELECT id,title,message,created_at,read_at FROM notifications WHERE owner_hash=? ORDER BY created_at DESC LIMIT 50",(rs,row)->linkedMap("id",rs.getString(1),"title",rs.getString(2),"message",rs.getString(3),"createdAt",rs.getObject(4,Instant.class).toString(),"read",rs.getObject(5)!=null),owner);}
  @PostMapping("/notifications/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT) public void readNotification(@PathVariable String id,HttpServletRequest request){String owner=existingOwner(request);if(owner==null||db.update("UPDATE notifications SET read_at=? WHERE id=? AND owner_hash=?",Instant.now(),id,owner)!=1)throw new Missing("알림을 찾을 수 없어요.");}

  @SuppressWarnings("unchecked") private void observeWatches(String date,String direction,Map<String,Object> base){
    List<Map<String,Object>> watches=db.query("SELECT id,owner_hash,provider_id,snapshot_json FROM watches WHERE scheduled_date=? AND direction=?",(rs,row)->linkedMap("id",rs.getString(1),"owner",rs.getString(2),"providerId",rs.getString(3),"snapshot",rs.getString(4)),date,direction);if(watches.isEmpty())return;
    Map<String,Map<String,Object>> byId=new LinkedHashMap<>();for(Map<String,Object> item:(List<Map<String,Object>>)base.getOrDefault("items",List.of()))byId.put(text(item,"id"),item);
    for(Map<String,Object> watch:watches){Map<String,Object> current=byId.get(String.valueOf(watch.get("providerId")));if(current==null)continue;try{Map<String,Object> previous=json.readValue(String.valueOf(watch.get("snapshot")),new TypeReference<Map<String,Object>>(){});List<String> changed=new ArrayList<>();for(String key:List.of("estimatedLocalTime","terminalDisplayName","arrivalExit","baggageCarousel","status"))if(!String.valueOf(previous.get(key)).equals(String.valueOf(current.get(key))))changed.add(key);if(!changed.isEmpty()){String changeKey=changed+"|"+changed.stream().map(key->String.valueOf(current.get(key))).toList();try{db.update("INSERT INTO notifications VALUES (?,?,?,?,?,?,NULL)",UUID.randomUUID().toString(),watch.get("owner"),watch.get("id"),changeKey,text(current,"displayNumber")+" 운항 정보가 변경됐어요",changeMessage(previous,current,changed),Instant.now());}catch(Exception ignored){}db.update("UPDATE watches SET snapshot_json=? WHERE id=?",toJson(snapshot(current)),watch.get("id"));}}catch(Exception e){LOG.warn("watch_compare_failed watch={}",watch.get("id"));}}
  }
  private String changeMessage(Map<String,Object> before,Map<String,Object> after,List<String> changed){Map<String,String> labels=Map.of("estimatedLocalTime","예상 시각","terminalDisplayName","터미널","arrivalExit","입국 출구","baggageCarousel","수하물 수취대","status","운항 상태");return changed.stream().map(key->labels.get(key)+" "+display(before.get(key))+" → "+display(after.get(key))).reduce((a,b)->a+" · "+b).orElse("운항 정보 변경");}

  @SuppressWarnings("unchecked") private Map<String,Object> verifiedFlight(String providerId,String date,String direction){if(providerId==null||providerId.isBlank())throw new Bad("항공편 식별값이 필요해요.");Map<String,Object> result=liveFlights(date,"",direction);return ((List<Map<String,Object>>)result.getOrDefault("items",List.of())).stream().filter(item->providerId.equals(text(item,"id"))).findFirst().orElseThrow(()->new Missing("선택한 항공편을 다시 확인해 주세요."));}
  private void refreshMeetupFromCache(Meetup meetup){if(meetup.providerId.isBlank())return;Map<String,Object> cached=readCache("majoong:flights:"+meetup.scheduledDate+":"+meetup.direction);if(cached==null)return;@SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)cached.getOrDefault("items",List.of());items.stream().filter(item->meetup.providerId.equals(text(item,"id"))).findFirst().ifPresent(item->db.update("UPDATE meetups SET scheduled=?,estimated=?,terminal=?,arrival_exit=?,baggage=?,source_observed_at=? WHERE id=?",text(item,"scheduledLocalTime"),text(item,"estimatedLocalTime"),text(item,"terminalDisplayName"),text(item,"arrivalExit"),text(item,"baggageCarousel"),parseInstant(item.get("observedAt"),meetup.sourceObservedAt),meetup.id));}

  private TripRequest normalizeTrip(TripRequest request){List<TripSegment> segments=new ArrayList<>();for(TripSegment segment:request.segments())segments.add(new TripSegment(firstNonBlank(segment.segmentId(),UUID.randomUUID().toString()),normalize(segment.flightNumber()),segment.date(),firstNonBlank(segment.dateBasis(),"DEPARTURE_LOCAL_DATE"),normalize(segment.origin()),normalize(segment.destination()),trim(segment.airline()),trim(segment.bookingReference()).toUpperCase(Locale.ROOT),trim(segment.departureTime()),trim(segment.arrivalTime()),trim(segment.terminal()),trim(segment.gate()),trim(segment.seat()).toUpperCase(Locale.ROOT),trim(segment.baggage()),trim(segment.memo()),trim(segment.providerId()),trim(segment.direction()).toUpperCase(Locale.ROOT),trim(segment.sourceState())));return new TripRequest(request.type().toUpperCase(Locale.ROOT),request.name().trim(),segments,request.expectedRevision());}
  private void validateTrip(TripRequest request){
    if(!TRIP_TYPES.contains(request.type()))throw new Bad("여정 유형이 올바르지 않아요.");if(request.type().equals("ONE_WAY")&&request.segments().size()!=1)throw new Bad("편도는 한 구간만 등록할 수 있어요.");if(request.type().equals("ROUND_TRIP")&&request.segments().size()!=2)throw new Bad("왕복은 가는 편과 오는 편 두 구간이 필요해요.");if(request.type().equals("MULTI_CITY")&&request.segments().size()<2)throw new Bad("다구간은 두 구간 이상이 필요해요.");LocalDate previous=null;
    for(TripSegment segment:request.segments()){if(!segment.origin().matches("[A-Z]{3}")||!segment.destination().matches("[A-Z]{3}"))throw new Bad("출발·도착 공항은 3자리 IATA 코드로 입력해 주세요.");if(segment.origin().equals(segment.destination()))throw new Bad("출발 공항과 도착 공항은 달라야 해요.");LocalDate current;try{current=LocalDate.parse(segment.date());}catch(Exception e){throw new Bad("여정 날짜를 확인해 주세요.");}if(previous!=null&&current.isBefore(previous))throw new Bad("여정 날짜는 시간순으로 입력해 주세요.");previous=current;}
    if(request.type().equals("ROUND_TRIP")&&(!request.segments().get(0).destination().equals(request.segments().get(1).origin())||!request.segments().get(0).origin().equals(request.segments().get(1).destination())))throw new Bad("왕복의 출발·도착 공항이 서로 맞지 않아요.");
  }
  private String tripState(List<TripSegment> segments){return segments.stream().allMatch(segment->!segment.providerId().isBlank())?"OFFICIAL_LINKED":"MANUAL_UNVERIFIED";}
  private Map<String,Object> requireTrip(String id,String owner){try{return db.queryForObject("SELECT id,trip_type,name,segments_json,state,created_at,revision,updated_at FROM trips WHERE id=? AND owner_hash=? AND deleted=FALSE",(rs,row)->tripMap(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,Instant.class),rs.getLong(7),rs.getObject(8,Instant.class)),id,owner);}catch(EmptyResultDataAccessException e){throw new Missing("여정을 찾을 수 없어요.");}}
  private Map<String,Object> tripMap(String id,String type,String name,String segments,String state,Instant createdAt,long revision,Instant updatedAt){return Map.of("id",id,"type",type,"name",name,"segments",readSegments(segments),"state",state,"revision",revision,"createdAt",createdAt.toString(),"updatedAt",updatedAt.toString());}
  private Object readSegments(String value){try{return json.readValue(value,Object.class);}catch(Exception e){throw new IllegalStateException("저장된 일정 형식을 읽을 수 없어요.");}}

  private Map<String,Object> shared(Meetup meetup){return linkedMap("meetupId",meetup.id,"revision",meetup.revision,"flight",Map.of("displayNumber",meetup.flight,"origin",meetup.origin,"destination",meetup.destination),"arrival",Map.of("scheduledLocalTime",meetup.scheduled,"estimatedLocalTime",meetup.estimated,"date",meetup.scheduledDate,"zoneId","Asia/Seoul"),"airportInfo",Map.of("terminal",meetup.terminal,"arrivalExit",meetup.exit,"baggageCarousel",meetup.baggage),"traveler",Map.of("status",meetup.status,"updatedAt",meetup.travelerUpdatedAt.toString()),"meetingPoint",Map.of("terminal",meetup.pointTerminal,"publicArea",meetup.pointArea,"exitLabel",meetup.pointExit,"landmark",meetup.pointLandmark,"sourceType",meetup.pointTerminal.isBlank()?"NONE":"USER_CONFIRMED"),"meetingNote",meetup.note,"officialDataState",meetup.providerId.isBlank()?"SNAPSHOT_AT_CREATION":"CACHE_REFRESHED","sourceObservedAt",meetup.sourceObservedAt.toString(),"meetingUpdatedAt",meetup.meetingUpdatedAt.toString());}
  private String meetupSelect(){return "SELECT id,owner_hash,revision,flight,origin,destination,scheduled,estimated,scheduled_date,terminal,arrival_exit,baggage,traveler_status,note,point_terminal,point_area,point_exit,point_landmark,completed,updated_at,provider_id,direction,source_observed_at,traveler_updated_at,meeting_updated_at FROM meetups";}
  private Meetup mapMeetup(java.sql.ResultSet rs,int row)throws java.sql.SQLException{return new Meetup(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15),rs.getString(16),rs.getString(17),rs.getString(18),rs.getBoolean(19),rs.getObject(20,Instant.class),rs.getString(21),rs.getString(22),rs.getObject(23,Instant.class),rs.getObject(24,Instant.class),rs.getObject(25,Instant.class));}
  private Meetup requireMeetup(String id){try{return db.queryForObject(meetupSelect()+" WHERE id=?",this::mapMeetup,id);}catch(EmptyResultDataAccessException e){throw new Missing("마중방을 찾을 수 없어요.");}}
  private void own(Meetup meetup,HttpServletRequest request){String owner=existingOwner(request);if(owner==null||!MessageDigest.isEqual(meetup.ownerHash.getBytes(StandardCharsets.UTF_8),owner.getBytes(StandardCharsets.UTF_8)))throw new Missing("마중방을 찾을 수 없어요.");}

  private String owner(HttpServletRequest request,HttpServletResponse response){String value=cookie(request);if(value==null){byte[] bytes=new byte[32];random.nextBytes(bytes);value=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(OWNER_COOKIE,value).httpOnly(true).secure(true).sameSite("Strict").path("/majoong/").maxAge(Duration.ofDays(90)).build().toString());}return hash(value);}
  private String existingOwner(HttpServletRequest request){String token=cookie(request);return token==null?null:hash(token);}private String cookie(HttpServletRequest request){var cookie=WebUtils.getCookie(request,OWNER_COOKIE);return cookie==null?null:cookie.getValue();}
  private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
  private void noStore(HttpServletResponse response){response.setHeader(HttpHeaders.CACHE_CONTROL,CacheControl.noStore().getHeaderValue());response.setHeader("Pragma","no-cache");}
  private void update(String id,long version,String sql,Object... values){Object[] parameters=Arrays.copyOf(values,values.length+2);parameters[parameters.length-2]=id;parameters[parameters.length-1]=version;if(db.update(sql,parameters)!=1)throw new Conflict("다른 화면에서 내용이 변경됐어요. 다시 불러와 주세요.");}

  private String normalizedDirection(String direction){String value=firstNonBlank(direction,"ARRIVAL").toUpperCase(Locale.ROOT);if(!DIRECTIONS.contains(value))throw new Bad("운항 방향을 확인해 주세요.");return value;}
  private String time(String value){return value!=null&&value.length()>=12?value.substring(8,10)+":"+value.substring(10,12):"미발표";}
  private String terminalDisplay(String raw){return switch(raw==null?"":raw){case "P01"->"제1여객터미널";case "P02"->"탑승동";case "P03"->"제2여객터미널";default->"미발표";};}
  private String firstNonBlank(String... values){for(String value:values)if(value!=null&&!value.isBlank())return value;return "";}private String blankAs(String value,String fallback){return value==null||value.isBlank()||"unknown".equalsIgnoreCase(value)?fallback:value;}private String trim(String value){return value==null?"":value.trim();}private String normalize(String value){return trim(value).replaceAll("\\s","").toUpperCase(Locale.ROOT);}private String text(Map<String,Object> map,String key){return display(map.get(key));}private String display(Object value){return value==null||String.valueOf(value).isBlank()?"미발표":String.valueOf(value);}private Instant parseInstant(Object value,Instant fallback){try{return Instant.parse(String.valueOf(value));}catch(Exception e){return fallback;}}private String toJson(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("데이터를 저장할 수 없어요.",e);}}
  private Map<String,Object> snapshot(Map<String,Object> item){return linkedMap("estimatedLocalTime",item.get("estimatedLocalTime"),"terminalDisplayName",item.get("terminalDisplayName"),"arrivalExit",item.get("arrivalExit"),"baggageCarousel",item.get("baggageCarousel"),"status",item.get("status"));}
  private Map<String,Object> guide(String code,String name,String mapSummary,String facilities,String mapUrl,String facilityUrl,String operator){return Map.of("code",code,"name",name,"mapSummary",mapSummary,"facilities",facilities,"mapUrl",mapUrl,"facilityUrl",facilityUrl,"operator",operator,"verifiedAt","2026-09-28");}
  private Map<String,Object> linkedMap(Object... values){Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)map.put(String.valueOf(values[i]),values[i+1]);return map;}

  @ExceptionHandler(Bad.class) ResponseEntity<Map<String,Object>> bad(Bad e){return error(HttpStatus.BAD_REQUEST,"INVALID_INPUT",e.getMessage(),false);}
  @ExceptionHandler(DateOutOfRange.class) ResponseEntity<Map<String,Object>> date(DateOutOfRange e){return error(HttpStatus.BAD_REQUEST,"DATE_OUT_OF_RANGE",e.getMessage(),false);}
  @ExceptionHandler(Missing.class) ResponseEntity<Map<String,Object>> missing(Missing e){return error(HttpStatus.NOT_FOUND,"NOT_FOUND",e.getMessage(),false);}
  @ExceptionHandler(Conflict.class) ResponseEntity<Map<String,Object>> conflict(Conflict e){return error(HttpStatus.CONFLICT,"VERSION_CONFLICT",e.getMessage(),true);}
  @ExceptionHandler(Gone.class) ResponseEntity<Map<String,Object>> gone(Gone e){return error(HttpStatus.GONE,"SHARE_ENDED",e.getMessage(),false);}
  @ExceptionHandler(SourceUnavailable.class) ResponseEntity<Map<String,Object>> unavailable(SourceUnavailable e){return error(HttpStatus.SERVICE_UNAVAILABLE,"SOURCE_UNAVAILABLE",e.getMessage(),true);}
  private ResponseEntity<Map<String,Object>> error(HttpStatus status,String code,String message,boolean retryable){return ResponseEntity.status(status).body(Map.of("code",code,"message",message,"retryable",retryable,"requestId",UUID.randomUUID().toString()));}

  public static class Meetup{
    public String id,flight,origin,destination,scheduled,estimated,scheduledDate,terminal,exit,baggage,status,note,pointTerminal,pointArea,pointExit,pointLandmark,providerId,direction;public long revision;public boolean completed;public Instant updatedAt,sourceObservedAt,travelerUpdatedAt,meetingUpdatedAt;@JsonIgnore public String ownerHash;
    Meetup(String id,String ownerHash,long revision,String flight,String origin,String destination,String scheduled,String estimated,String scheduledDate,String terminal,String exit,String baggage,String status,String note,String pointTerminal,String pointArea,String pointExit,String pointLandmark,boolean completed,Instant updatedAt,String providerId,String direction,Instant sourceObservedAt,Instant travelerUpdatedAt,Instant meetingUpdatedAt){this.id=id;this.ownerHash=ownerHash;this.revision=revision;this.flight=flight;this.origin=origin;this.destination=destination;this.scheduled=scheduled;this.estimated=estimated;this.scheduledDate=scheduledDate;this.terminal=terminal;this.exit=exit;this.baggage=baggage;this.status=status;this.note=note;this.pointTerminal=pointTerminal;this.pointArea=pointArea;this.pointExit=pointExit;this.pointLandmark=pointLandmark;this.completed=completed;this.updatedAt=updatedAt;this.providerId=providerId;this.direction=direction;this.sourceObservedAt=sourceObservedAt;this.travelerUpdatedAt=travelerUpdatedAt;this.meetingUpdatedAt=meetingUpdatedAt;}
  }
  record FlightRow(String id,String number,String schedule,String estimated,String airportCode,String airportName,String terminal,String exit,String baggage,String status){}record JsonNodeResult(int total,List<FlightRow> rows,int pages,boolean partial){}record Share(String tokenHash,String meetup,Instant expiry,boolean revoked){}
  record CreateMeetupRequest(@NotBlank String providerId,@NotBlank @Pattern(regexp="\\d{4}-\\d{2}-\\d{2}") String scheduledDate,@NotBlank String direction){}record StatusRequest(@NotBlank String status,@PositiveOrZero long expectedVersion){}record NoteRequest(@NotBlank @Size(max=300) String text,@PositiveOrZero long expectedVersion){}record MeetingPointRequest(@NotBlank @Size(max=160) String terminal,@NotBlank @Size(max=120) String publicArea,@NotBlank @Size(max=40) String exitLabel,@NotBlank @Size(max=160) String landmark,@NotBlank @Size(max=300) String note,@PositiveOrZero long expectedVersion){}record CompleteRequest(@PositiveOrZero long expectedVersion){}record TokenRequest(@NotBlank @Size(max=128) String token){}record WatchRequest(@NotBlank String providerId,@NotBlank @Pattern(regexp="\\d{4}-\\d{2}-\\d{2}") String scheduledDate,@NotBlank String direction){}
  record TripSegment(String segmentId,@Size(max=20) String flightNumber,@NotBlank @Pattern(regexp="\\d{4}-\\d{2}-\\d{2}") String date,String dateBasis,@NotBlank String origin,@NotBlank String destination,@Size(max=80) String airline,@Size(max=40) String bookingReference,@Size(max=5) String departureTime,@Size(max=5) String arrivalTime,@Size(max=40) String terminal,@Size(max=20) String gate,@Size(max=20) String seat,@Size(max=80) String baggage,@Size(max=300) String memo,String providerId,String direction,String sourceState){}record TripRequest(@NotBlank String type,@NotBlank @Size(max=120) String name,@NotEmpty List<@Valid TripSegment> segments,Long expectedRevision){}
  static class Bad extends RuntimeException{Bad(String message){super(message);}}static class DateOutOfRange extends RuntimeException{DateOutOfRange(String message){super(message);}}static class Missing extends RuntimeException{Missing(String message){super(message);}}static class Conflict extends RuntimeException{Conflict(String message){super(message);}}static class Gone extends RuntimeException{Gone(String message){super(message);}}static class SourceUnavailable extends RuntimeException{SourceUnavailable(String message){super(message);}}
}
