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
    JSONObject transcribe(File audio,String key,String language)throws Exception{
        if(audio.length()>25_000_000)throw new HttpApi.UserAction("Este bloque supera el tamaño permitido por OpenAI.");
        String boundary="VozLocal"+java.util.UUID.randomUUID().toString().replace("-","");
        String header=field(boundary,"model","gpt-4o-transcribe-diarize")+field(boundary,"response_format","diarized_json")+field(boundary,"chunking_strategy","auto");
        if(!language.isEmpty())header+=field(boundary,"language",language);
        header+="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n";
        byte[] prefix=header.getBytes(StandardCharsets.UTF_8),suffix=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpApi.Body body=new HttpApi.Body(){public long length(){return prefix.length+audio.length()+suffix.length;}public void write(OutputStream out)throws Exception{out.write(prefix);http.copy(audio,out);out.write(suffix);}};
        HttpApi.Response response=http.request("POST","https://api.openai.com/v1/audio/transcriptions",key,"multipart/form-data; boundary="+boundary,body,null);
        HttpApi.require(response,"OpenAI");return response.json();
    }
    private static String field(String boundary,String key,String value){return "--"+boundary+"\r\nContent-Disposition: form-data; name=\""+key+"\"\r\n\r\n"+value+"\r\n";}
}
