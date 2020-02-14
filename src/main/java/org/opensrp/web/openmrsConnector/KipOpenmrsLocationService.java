package org.opensrp.web.openmrsConnector;

import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opensrp.api.domain.Location;
import org.opensrp.common.util.HttpResponse;
import org.opensrp.common.util.HttpUtil;
import org.opensrp.connector.openmrs.service.OpenmrsLocationService;

import org.springframework.stereotype.Service;


@Service
public class KipOpenmrsLocationService extends OpenmrsLocationService {

    private static final String LOCATION_URL = "ws/rest/v1/location";
    private static final String COUNTY = "County";
    private static final String SUB_COUNTY = "Sub County";
    private static final String WARD = "Ward";

    public KipOpenmrsLocationService() {	}

    public KipOpenmrsLocationService(String openmrsUrl, String user, String password) {
        super(openmrsUrl, user, password);
    }

    private Location makeLocation(String locationJson) throws JSONException {
        JSONObject obj = new JSONObject(locationJson);
        Location p = getParent(obj);
        Location l = new Location(obj.getString("uuid"), obj.getString("name"), null, null, p, null, null);
        JSONArray t = obj.getJSONArray("tags");

        for (int i = 0; i < t.length(); i++) {
            l.addTag(t.getJSONObject(i).getString("display"));
        }

        JSONArray a = obj.getJSONArray("attributes");

        for (int i = 0; i < a.length(); i++) {
            String ad = a.getJSONObject(i).getString("display");
            l.addAttribute(ad.substring(0, ad.indexOf(":")), ad.substring(ad.indexOf(":") + 2));
        }

        return l;
    }

    private Location makeLocation(JSONObject location) throws JSONException {
        return makeLocation(location.toString());
    }

    public JSONObject getLocationTree(String[] locationIdsOrNames) throws JSONException {
        JSONArray locations = new JSONArray();

        for (String loc : locationIdsOrNames) {
            HttpResponse op = HttpUtil.get(HttpUtil.removeEndingSlash(this.OPENMRS_BASE_URL)+"/"+LOCATION_URL+"/"+(loc.replaceAll(" ", "%20")), "v=full", this.OPENMRS_USER, this.OPENMRS_PWD);
            JSONObject lo = new JSONObject(op.body());

            fillTreeWithLowerHierarchy(locations, lo);
        }

        return new JSONObject().put("userLocations", locations);
    }

    private String fillTreeWithLowerHierarchy(JSONArray locations, JSONObject lo) throws JSONException{

        Location l = null;
        if(lo.has("tags")){
            for (int n = 0; n < lo.getJSONArray("tags").length(); n++) {
                String tag = lo.getJSONArray("tags").getJSONObject(n).getString("display");
                if(tag.equals(COUNTY) || tag.equals(SUB_COUNTY) || tag.equals(WARD))
                    l = makeLocation(lo);
            }
        }

        if(l != null)
            locations.put(new Gson().toJson(l));

        if(l != null && lo.has("childLocations")){
            JSONArray lch = lo.getJSONArray("childLocations");

            for (int i = 0; i < lch.length(); i++) {

                JSONObject cj = lch.getJSONObject(i);
                boolean proceed = false;

                if(cj.has("tags")){
                    for (int n = 0; n < cj.getJSONArray("tags").length(); n++) {
                        String tag = cj.getJSONArray("tags").getJSONObject(n).getString("display");
                        if(tag.equals(COUNTY) || tag.equals(SUB_COUNTY) || tag.equals(WARD))
                            proceed = true;
                    }
                } else {
                    if(l.getTags().contains(COUNTY) || l.getTags().contains(SUB_COUNTY)){
                        proceed = true;
                    }
                }

                if(proceed) {
                    if (cj.has("name")) {
                        fillTreeWithLowerHierarchy(locations, cj);
                    } else {
                        String uuid = cj.has("uuid") ? cj.getString("uuid") : "";
                        if (org.apache.commons.lang3.StringUtils.isNotBlank(uuid)) {
                            HttpResponse op = HttpUtil.get(HttpUtil.removeEndingSlash(this.OPENMRS_BASE_URL) + "/" + LOCATION_URL + "/" + (uuid.replaceAll(" ", "%20")), "v=full", this.OPENMRS_USER, this.OPENMRS_PWD);

                            fillTreeWithLowerHierarchy(locations, new JSONObject(op.body()));
                        }
                    }
                }
            }
        }
        return l == null ? null : l.getLocationId();
    }
}
