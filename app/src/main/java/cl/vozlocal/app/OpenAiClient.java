package cl.vozlocal.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;

/**
 * Cliente de /audio/transcriptions. Cada modelo recibe exactamente los parámetros que admite:
 * - gpt-4o-transcribe-diarize: diarized_json + chunking_strategy + language (+ muestras de voz opcionales).
 * - gpt-transcribe: languages[] (NO acepta "language") y streaming del texto.
 * - resto: json + language.
 */
final class OpenAiClient {
    final HttpApi http;
    OpenAiClient(HttpApi http){this.http=http;}
    interface Delta{void text(int characters);}
    void verify(String key)throws Exception{
        HttpApi.Response response=http.request("GET","https://api.openai.com/v1/models/gpt-4o-transcribe-diarize",key,null,null,null);
        HttpApi.require(response,"OpenAI");
    }
    void verify(ProviderConfig config)throws Exception{HttpApi.Response response=http.request("GET",config.base+"/models/"+java.net.URLEncoder.encode(config.model,"UTF-8"),config.key,null,null,null);HttpApi.require(response,"Proveedor");}
    JSONObject transcribe(File audio,String key,String language)throws Exception{
        return transcribe(audio,new ProviderConfig("openai","https://api.openai.com/v1","gpt-4o-transcribe-diarize",key,true),language);
    }
    JSONObject transcribe(File audio,ProviderConfig config,String language)throws Exception{return transcribe(audio,config,language,null,null);}
    /** references: pares {nombre, data URL} de muestras de voz (2–10 s) para reconocer a las mismas personas en otros bloques. */
    JSONObject transcribe(File audio,ProviderConfig config,String language,List<String[]> references,Delta delta)throws Exception{
        if(audio.length()>25_000_000)throw new HttpApi.UserAction("Este bloque supera el tamaño permitido por el proveedor (25 MB).");
        String boundary="VozLocal"+java.util.UUID.randomUUID().toString().replace("-","");
        boolean fast=config.provider.equals("openai")&&config.model.equals("gpt-transcribe");
        StringBuilder header=new StringBuilder(field(boundary,"model",config.model));
        if(config.speakers){
            header.append(field(boundary,"response_format","diarized_json")).append(field(boundary,"chunking_strategy","auto"));
            if(!language.isEmpty())header.append(field(boundary,"language",language));
            if(references!=null)for(String[] ref:references)header.append(field(boundary,"known_speaker_names[]",ref[0])).append(field(boundary,"known_speaker_references[]",ref[1]));
        }else if(fast){
            if(!language.isEmpty())header.append(field(boundary,"languages[]",language));
            if(delta!=null)header.append(field(boundary,"stream","true"));
        }else{
            header.append(field(boundary,"response_format","json"));
            if(!language.isEmpty())header.append(field(boundary,"language",language));
        }
        header.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n");
        byte[] prefix=header.toString().getBytes(StandardCharsets.UTF_8),suffix=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpApi.Body body=new HttpApi.Body(){public long length(){return prefix.length+audio.length()+suffix.length;}public void write(OutputStream out)throws Exception{out.write(prefix);http.copy(audio,out);out.write(suffix);}};
        if(fast&&delta!=null){int[] chars={0};http.onEvent=event->{if("transcript.text.delta".equals(event.optString("type"))){chars[0]+=event.optString("delta").length();delta.text(chars[0]);}};}
        HttpApi.Response response;
        try{response=http.request("POST",config.base+"/audio/transcriptions",config.key,"multipart/form-data; boundary="+boundary,body,null);}finally{http.onEvent=null;}
        HttpApi.require(response,"Proveedor");JSONObject result=response.json();
        if(!config.speakers){if(!result.has("text"))throw new HttpApi.UserAction("El proveedor no devolvió texto en el formato esperado.");org.json.JSONArray segments=new org.json.JSONArray();if(!result.getString("text").trim().isEmpty())segments.put(new JSONObject().put("speaker","text").put("start",0).put("end",0).put("text",result.getString("text")));result.put("segments",segments);}
        return result.put("_diarized",config.speakers);
    }
    private static String field(String boundary,String key,String value){return "--"+boundary+"\r\nContent-Disposition: form-data; name=\""+key+"\"\r\n\r\n"+value+"\r\n";}
}
