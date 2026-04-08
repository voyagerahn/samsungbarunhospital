package io.falab.samsungbarunhospital.model;

import org.json.JSONObject;

public class ButtonConfig {

    public static final String TYPE_APP = "app";
    public static final String TYPE_WEB = "web";

    public String imagePath;   // 내부 저장소 절대경로 (관리자가 업로드한 이미지)
    public String drawableRes; // 기본 drawable 리소스명 (예: "youtube"), imagePath 없을 때 사용
    public String type;        // TYPE_APP or TYPE_WEB
    public String target;      // 패키지명 or URL

    public ButtonConfig() {}

    public ButtonConfig(String imagePath, String drawableRes, String type, String target) {
        this.imagePath   = imagePath;
        this.drawableRes = drawableRes;
        this.type        = type;
        this.target      = target;
    }

    public JSONObject toJson() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("imagePath",   imagePath   != null ? imagePath   : "");
        obj.put("drawableRes", drawableRes != null ? drawableRes : "");
        obj.put("type",        type        != null ? type        : TYPE_WEB);
        obj.put("target",      target      != null ? target      : "");
        return obj;
    }

    public static ButtonConfig fromJson(JSONObject obj) throws Exception {
        ButtonConfig c = new ButtonConfig();
        String path = obj.optString("imagePath", "");
        String res  = obj.optString("drawableRes", "");
        c.imagePath   = path.isEmpty() ? null : path;
        c.drawableRes = res.isEmpty()  ? null : res;
        c.type        = obj.optString("type",   TYPE_WEB);
        c.target      = obj.optString("target", "");
        return c;
    }
}
