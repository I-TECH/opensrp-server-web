package org.opensrp.web.rest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.reflect.TypeToken;
import com.mysql.jdbc.StringUtils;
import org.joda.time.DateTime;
import org.json.JSONException;
import org.json.JSONObject;
import org.opensrp.common.AllConstants.BaseEntity;
import org.opensrp.domain.Client;
import org.opensrp.domain.Event;
import org.opensrp.search.EventSearchBean;
import org.opensrp.service.ClientService;
import org.opensrp.service.EventService;
import org.opensrp.util.DateTimeTypeConverter;
import org.opensrp.web.bean.SyncParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.text.ParseException;
import java.util.*;

import static java.text.MessageFormat.format;
import static org.opensrp.common.AllConstants.BaseEntity.BASE_ENTITY_ID;
import static org.opensrp.common.AllConstants.BaseEntity.LAST_UPDATE;
import static org.opensrp.common.AllConstants.CLIENTS_FETCH_BATCH_SIZE;
import static org.opensrp.common.AllConstants.Event.*;
import static org.opensrp.web.rest.RestUtils.*;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@Controller
@RequestMapping(value = "/rest/event")
public class EventResource extends RestResource<Event> {
	
	public static final String DATE_DELETED = "dateDeleted";
	private static Logger logger = LoggerFactory.getLogger(EventResource.class.toString());
	Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
	        .registerTypeAdapter(DateTime.class, new DateTimeTypeConverter()).create();
	private EventService eventService;
	private ClientService clientService;
	@Value("#{opensrp['opensrp.sync.search.missing.client']}")
	private boolean searchMissingClients;
	
	@Autowired
	public EventResource(ClientService clientService, EventService eventService) {
		this.clientService = clientService;
		this.eventService = eventService;
	}
	
	public static void main(String[] args) {

	}
	
	@Override
	public Event getByUniqueId(String uniqueId) {
		return eventService.find(uniqueId);
	}
	
	/**
	 * Get an event using the event id
	 *
	 * @param eventId the event id
	 * @return event with the event id
	 */
	@RequestMapping(value = "/findById", method = RequestMethod.GET, produces = { MediaType.APPLICATION_JSON_VALUE })
	public Event getById(@RequestParam("id") String eventId) {
		return eventService.findById(eventId);
	}
	
	/**
	 * Fetch events ordered by serverVersion ascending order and return the clients associated with the
	 * events
	 *
	 * @param request
	 * @return a map response with events, clients and optionally msg when an error occurs
	 */
	@RequestMapping(value = "/sync", method = RequestMethod.GET, produces = { MediaType.APPLICATION_JSON_VALUE })
	@ResponseBody
	protected ResponseEntity<String> sync(HttpServletRequest request) {
		Map<String, Object> response = new HashMap<String, Object>();
		try {
			String providerId = getStringFilter(PROVIDER_ID, request);
			String locationId = getStringFilter(LOCATION_ID, request);
			String baseEntityId = getStringFilter(BASE_ENTITY_ID, request);
			String serverVersion = getStringFilter(BaseEntity.SERVER_VERSIOIN, request);
			String team = getStringFilter(TEAM, request);
			String teamId = getStringFilter(TEAM_ID, request);
			Integer limit = getIntegerFilter("limit", request);

			if (team != null || providerId != null || locationId != null || baseEntityId != null || teamId != null) {

				return new ResponseEntity<>(
				        gson.toJson(sync(providerId, locationId, baseEntityId, serverVersion, team, teamId, limit)),
				        RestUtils.getJSONUTF8Headers(), HttpStatus.OK);
			} else {
				response.put("msg", "specify atleast one filter");
				return new ResponseEntity<>(new Gson().toJson(response), BAD_REQUEST);
			}

		}
		catch (

		Exception e) {

			response.put("msg", "Error occurred");
			logger.error("", e);
			return new ResponseEntity<>(new Gson().toJson(response), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	/**
	 * Fetch events ordered by serverVersion ascending order and return the clients associated with the
	 * events
	 *
	 * @param request
	 * @return a map response with events, clients and optionally msg when an error occurs
	 */
	@RequestMapping(value = "/sync", method = POST, produces = { MediaType.APPLICATION_JSON_VALUE })
	@ResponseBody
	protected ResponseEntity<String> syncByPost(@RequestBody SyncParam syncParam) {
		Map<String, Object> response = new HashMap<String, Object>();
		try {

			if (syncParam.getTeam() != null || syncParam.getProviderId() != null || syncParam.getLocationId() != null
			        || syncParam.getBaseEntityId() != null || syncParam.getTeamId() != null) {

				return new ResponseEntity<>(
				        gson.toJson(sync(syncParam.getProviderId(), syncParam.getLocationId(), syncParam.getBaseEntityId(),
				            syncParam.getServerVersion(), syncParam.getTeam(), syncParam.getTeamId(), syncParam.getLimit())),
				        RestUtils.getJSONUTF8Headers(), HttpStatus.OK);
			} else {
				response.put("msg", "specify atleast one filter");
				return new ResponseEntity<>(new Gson().toJson(response), BAD_REQUEST);
			}

		}
		catch (Exception e) {

			response.put("msg", "Error occurred");
			logger.error("", e);
			return new ResponseEntity<>(new Gson().toJson(response), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	/**
	 * Fetch clients and associated events allongside family registration events for the family that
	 * they attached to for the list of base entity ids passed
	 *
	 * @param jsonObject Json Object containing a jsonArray with baseEntityIds, and an optional boolean
	 *            named withFamilyEvents for obtaining family events if the value passed is true.
	 * @return a map response with events, clients and optionally msg when an error occurs
	 */
	@RequestMapping(value = "/sync-by-base-entity-ids", method = POST, produces = { MediaType.APPLICATION_JSON_VALUE })
	@ResponseBody
	public ResponseEntity<String> syncClientsAndEventsByBaseEntityIds(@RequestBody String jsonObject) {
		Map<String, Object> response = new HashMap<>();
		List<Object> clientsEventsList = new ArrayList<>();

		try {
			JSONObject object = new JSONObject(jsonObject);
			boolean withFamilyEvents = false;
			try {
				withFamilyEvents = object.getBoolean("withFamilyEvents");
			}
			catch (JSONException e) {
				logger.error("", e);
			}

			List<String> baseEntityIdsList = gson.fromJson(object.getJSONArray("baseEntityIds").toString(),
			    new TypeToken<ArrayList<String>>() {}.getType());
			for (String baseEntityId : baseEntityIdsList) {
				Map<String, Object> clientEventsMap = sync(null, null, baseEntityId, "0", null, null, null);

				if (clientEventsMap.containsKey("clients")) {
					List<Client> clients = gson.fromJson(gson.toJson(clientEventsMap.get("clients")),
					    new TypeToken<List<Client>>() {}.getType());

					//Obtaining family registration events for client's family if withFamilyEvents is true.
					if (clients.size() == 1 && clients.get(0).getRelationships().containsKey("family") && withFamilyEvents) {
						List<String> clientRelationships = clients.get(0).getRelationships().get("family");
						for (String familyRelationship : clientRelationships) {
							Map<String, Object> familyEvents = sync(null, null, familyRelationship, "0", null, null, null);

							JsonArray events = (JsonArray) gson.toJsonTree(clientEventsMap.get("events"));
							events.addAll((JsonArray) gson.toJsonTree(familyEvents.get("events")));

							//adding the family registration events to the client's events list
							clientEventsMap.put("events", events);
						}

					}
					clientsEventsList.add(clientEventsMap);
				}
			}

			return new ResponseEntity<>(gson.toJson(clientsEventsList), RestUtils.getJSONUTF8Headers(), HttpStatus.OK);

		}
		catch (Exception e) {
			response.put("msg", "Error occurred");
			logger.error("", e);
			return new ResponseEntity<>(new Gson().toJson(response), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	protected Map<String, Object> sync(String providerId, String locationId, String baseEntityId, String serverVersion,
	        String team, String teamId, Integer limit) {
		Long lastSyncedServerVersion = null;
		if (serverVersion != null) {
			lastSyncedServerVersion = Long.valueOf(serverVersion) + 1;
		}

		EventSearchBean eventSearchBean = new EventSearchBean();
		eventSearchBean.setTeam(team);
		eventSearchBean.setTeamId(teamId);
		eventSearchBean.setProviderId(providerId);
		eventSearchBean.setLocationId(locationId);
		eventSearchBean.setBaseEntityId(baseEntityId);
		eventSearchBean.setServerVersion(lastSyncedServerVersion);

		return getEventsAndClients(eventSearchBean, limit == null || limit.intValue() == 0 ? 25 : limit);

	}
	
	private Map<String, Object> getEventsAndClients(EventSearchBean eventSearchBean, Integer limit) {
		Map<String, Object> response = new HashMap<String, Object>();
		List<Event> events = new ArrayList<Event>();
		List<String> clientIds = new ArrayList<String>();
		List<Client> clients = new ArrayList<Client>();
		long startTime = System.currentTimeMillis();
		events = eventService.findEvents(eventSearchBean, BaseEntity.SERVER_VERSIOIN, "asc", limit == null ? 25 : limit);
		logger.info("fetching events took: " + (System.currentTimeMillis() - startTime));
		if (!events.isEmpty()) {
			for (Event event : events) {
				if (org.apache.commons.lang.StringUtils.isNotBlank(event.getBaseEntityId())
				        && !clientIds.contains(event.getBaseEntityId())) {
					clientIds.add(event.getBaseEntityId());
				}
			}
			for (int i = 0; i < clientIds.size(); i = i + CLIENTS_FETCH_BATCH_SIZE) {
				int end = i + CLIENTS_FETCH_BATCH_SIZE < clientIds.size() ? i + CLIENTS_FETCH_BATCH_SIZE : clientIds.size();
				clients.addAll(clientService.findByFieldValue(BASE_ENTITY_ID, clientIds.subList(i, end)));
			}
			logger.info("fetching clients took: " + (System.currentTimeMillis() - startTime));

			searchMissingClients(clientIds, clients, startTime);
		}

		JsonArray eventsArray = (JsonArray) gson.toJsonTree(events, new TypeToken<List<Event>>() {}.getType());

		JsonArray clientsArray = (JsonArray) gson.toJsonTree(clients, new TypeToken<List<Client>>() {}.getType());

		response.put("events", eventsArray);
		response.put("clients", clientsArray);
		response.put("no_of_events", events.size());
		return response;
	}
	
	private void searchMissingClients(List<String> clientIds, List<Client> clients, long startTime) {
		if (searchMissingClients) {

			List<String> foundClientIds = new ArrayList<>();
			for (Client client : clients) {
				foundClientIds.add(client.getBaseEntityId());
			}

			boolean removed = clientIds.removeAll(foundClientIds);
			if (removed) {
				for (String clientId : clientIds) {
					Client client = clientService.getByBaseEntityId(clientId);
					if (client != null) {
						clients.add(client);
					}
				}
			}
			logger.info("fetching missing clients took: " + (System.currentTimeMillis() - startTime));
		}
	}
	
	/**
	 * Fetch events ordered by serverVersion ascending order and return the clients associated with the
	 * events
	 *
	 * @param request
	 * @return a map response with events, clients and optionally msg when an error occurs
	 */
	@RequestMapping(value = "/getAll", method = RequestMethod.GET, produces = { MediaType.APPLICATION_JSON_VALUE })
	@ResponseBody
	protected ResponseEntity<String> getAll(@RequestParam long serverVersion,
	        @RequestParam(required = false) String eventType, @RequestParam(required = false) Integer limit) {

		try {
			EventSearchBean eventSearchBean = new EventSearchBean();
			eventSearchBean.setServerVersion(serverVersion > 0 ? serverVersion + 1 : serverVersion);
			eventSearchBean.setEventType(eventType);
			return new ResponseEntity<>(gson.toJson(getEventsAndClients(eventSearchBean, limit == null ? 25 : limit)),
			        RestUtils.getJSONUTF8Headers(), HttpStatus.OK);

		}
		catch (Exception e) {
			Map<String, Object> response = new HashMap<String, Object>();
			response.put("msg", "Error occurred");
			logger.error("", e);
			return new ResponseEntity<>(new Gson().toJson(response), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	/*
	 * @RequestMapping(method=RequestMethod.GET)
	 *
	 * @ResponseBody public Event getByBaseEntityAndFormSubmissionId(@RequestParam
	 * String baseEntityId, @RequestParam String formSubmissionId) { return
	 * eventService.getByBaseEntityAndFormSubmissionId(baseEntityId,
	 * formSubmissionId); }
	 */
	
	@SuppressWarnings("unchecked")
	@RequestMapping(headers = { "Accept=application/json" }, method = POST, value = "/add")
	public ResponseEntity<HttpStatus> save(@RequestBody String data) {
		try {
			JSONObject syncData = new JSONObject(data);
			if (!syncData.has("clients") && !syncData.has("events")) {
				return new ResponseEntity<>(BAD_REQUEST);
			}

			if (syncData.has("clients")) {

				ArrayList<Client> clients = (ArrayList<Client>) gson.fromJson(syncData.getString("clients"),
				    new TypeToken<ArrayList<Client>>() {}.getType());
				for (Client client : clients) {
					try {
						clientService.addorUpdate(client);
					}
					catch (Exception e) {
						logger.error(
						    "Client" + client.getBaseEntityId() == null ? "" : client.getBaseEntityId() + " failed to sync",
						    e);
					}
				}

			}
			if (syncData.has("events")) {
				ArrayList<Event> events = (ArrayList<Event>) gson.fromJson(syncData.getString("events"),
				    new TypeToken<ArrayList<Event>>() {}.getType());
				for (Event event : events) {
					try {
						event = eventService.processOutOfArea(event);
						eventService.addorUpdateEvent(event);
					}
					catch (Exception e) {
						logger.error(
						    "Event of type " + event.getEventType() + " for client " + event.getBaseEntityId() == null ? ""
						            : event.getBaseEntityId() + " failed to sync",
						    e);
					}
				}
			}

		}
		catch (

		Exception e) {
			logger.error(format("Sync data processing failed with exception {0}.- ", e));
			return new ResponseEntity<>(INTERNAL_SERVER_ERROR);
		}
		return new ResponseEntity<>(CREATED);
	}
	
	@Override
	public Event create(Event o) {
		return eventService.addEvent(o);
	}
	
	@Override
	public List<String> requiredProperties() {
		List<String> p = new ArrayList<>();
		p.add(BASE_ENTITY_ID);
		// p.add(FORM_SUBMISSION_ID);
		p.add(EVENT_TYPE);
		// p.add(LOCATION_ID);
		// p.add(EVENT_DATE);
		p.add(PROVIDER_ID);
		// p.add(ENTITY_TYPE);
		return p;
	}
	
	@Override
	public Event update(Event entity) {
		return eventService.mergeEvent(entity);
	}
	
	@Override
	public List<Event> search(HttpServletRequest request) throws ParseException {
		String clientId = getStringFilter("identifier", request);
		DateTime[] eventDate = getDateRangeFilter(EVENT_DATE, request);// TODO
		String eventType = getStringFilter(EVENT_TYPE, request);
		String location = getStringFilter(LOCATION_ID, request);
		String provider = getStringFilter(PROVIDER_ID, request);
		String entityType = getStringFilter(ENTITY_TYPE, request);
		DateTime[] lastEdit = getDateRangeFilter(LAST_UPDATE, request);
		String team = getStringFilter(TEAM, request);
		String teamId = getStringFilter(TEAM_ID, request);
		
		if (!StringUtils.isEmptyOrWhitespaceOnly(clientId)) {
			Client c = clientService.find(clientId);
			if (c == null) {
				return new ArrayList<>();
			}
			
			clientId = c.getBaseEntityId();
		}
		EventSearchBean eventSearchBean = new EventSearchBean();
		eventSearchBean.setBaseEntityId(clientId);
		eventSearchBean.setEventDateFrom(eventDate == null ? null : eventDate[0]);
		eventSearchBean.setEventDateTo(eventDate == null ? null : eventDate[1]);
		eventSearchBean.setEventType(eventType);
		eventSearchBean.setEntityType(entityType);
		eventSearchBean.setProviderId(provider);
		eventSearchBean.setLocationId(location);
		eventSearchBean.setLastEditFrom(lastEdit == null ? null : lastEdit[0]);
		eventSearchBean.setLastEditTo(lastEdit == null ? null : lastEdit[1]);
		eventSearchBean.setTeam(team);
		eventSearchBean.setTeamId(teamId);
		
		return eventService.findEventsBy(eventSearchBean);
	}
	
	@Override
	public List<Event> filter(String query) {
		return eventService.findEventsByDynamicQuery(query);
	}
	
	/**
	 * Fetch events ids filtered by eventType
	 *
	 * @param eventType
	 * @return A list of event ids
	 */
	@RequestMapping(value = "/findIdsByEventType", method = RequestMethod.GET, produces = {
	        MediaType.APPLICATION_JSON_VALUE })
	@ResponseBody
	protected ResponseEntity<String> getAllIdsByEventType(
	        @RequestParam(value = EVENT_TYPE, required = false) String eventType,
	        @RequestParam(value = DATE_DELETED, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date dateDeleted) {
		
		try {
			
			List<String> eventIds = eventService.findAllIdsByEventType(eventType, dateDeleted);
			return new ResponseEntity<>(gson.toJson(eventIds), RestUtils.getJSONUTF8Headers(), HttpStatus.OK);
			
		}
		catch (Exception e) {
			logger.error(e.getMessage(), e);
			return new ResponseEntity<>(INTERNAL_SERVER_ERROR);
		}
	}
	
	public void setEventService(EventService eventService) {
		this.eventService = eventService;
	}
	
	public void setClientService(ClientService clientService) {
		this.clientService = clientService;
	}
	
}
