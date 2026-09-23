package cl.vozlocal.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class OpenAiClient {
    final HttpApi http;
    OpenAiClient(HttpApi http){this.http=http;}
    void verify(String key)throws Exception{
        HttpApi.Response response=http.request("GET","https://api.openai.com/v1/models/gpt-4o-transcribe-diarize",key,null,null,null);
        HttpApi.require(response,"OpenAI");
    }
    void verify(ProviderConfig config)throws Exception{HttpApi.Response response=http.request("GET",config.base+"/models/"+java.net.URLEncoder.encode(config.model,"UTF-8"),config.key,null,null,null);HttpApi.require(response,"Proveedor");}
    JSONObject transcribe(File audio,String key,String language)throws Exception{
        return transcribe(audio,new ProviderConfig("openai","https://api.openai.com/v1","gpt-4o-transcribe-diarize",key,true),language);
    }
    JSONObject transcribe(File audio,ProviderConfig config,String language)throws Exception{
        if(audio.length()>25_000_000)throw new HttpApi.UserAction("Este bloque supera el tamaño permitido por OpenAI.");
        String boundary="VozLocal"+java.util.UUID.randomUUID().toString().replace("-","");
        String header=field(boundary,"model",config.model)+field(boundary,"response_format",config.speakers?"diarized_json":"json");
        if(config.speakers)header+=field(boundary,"chunking_strategy","auto");
        if(!language.isEmpty())header+=field(boundary,"language",language);
        header+="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n";
        byte[] prefix=header.getBytes(StandardCharsets.UTF_8),suffix=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpApi.Body body=new HttpApi.Body(){public long length(){return prefix.length+audio.length()+suffix.length;}public void write(OutputStream out)throws Exception{out.write(prefix);http.copy(audio,out);out.write(suffix);}};
        HttpApi.Response response=http.request("POST",config.base+"/audio/transcriptions",config.key,"multipart/form-data; boundary="+boundary,body,null);
        HttpApi.require(response,"Proveedor");JSONObject result=response.json();
        if(!config.speakers){if(!result.has("text"))throw new HttpApi.UserAction("El proveedor no devolvió texto en el formato esperado.");org.json.JSONArray segments=new org.json.JSONArray();if(!result.getString("text").trim().isEmpty())segments.put(new JSONObject().put("speaker","text").put("start",0).put("end",0).put("text",result.getString("text")));result.put("segments",segments);}
        return result.put("_diarized",config.speakers);
    }
    private static String field(String boundary,String key,String value){return "--"+boundary+"\r\nContent-Disposition: form-data; name=\""+key+"\"\r\n\r\n"+value+"\r\n";}
}
