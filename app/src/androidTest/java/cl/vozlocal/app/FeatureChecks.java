package cl.vozlocal.app;

import android.content.Context;
import org.json.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class FeatureChecks {
    static void assertThat(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    static void run(Context c,Recording r)throws Exception{
        Settings settings=new Settings(c);String previousKey=settings.prefs.getString("keyEncrypted",null),previousIv=settings.prefs.getString("keyIv",null);
        boolean wifi=settings.wifiOnly(),charging=settings.charging();
        try{
            settings.prefs.edit().putBoolean("wifi",true).putBoolean("charging",true).commit();
            android.app.job.JobInfo job=Pipeline.jobInfo(c,settings);
            assertThat(job.getNetworkType()==android.app.job.JobInfo.NETWORK_TYPE_UNMETERED && job.isRequireCharging(),"Wi-Fi/charging constraints ignored");
            settings.prefs.edit().putBoolean("wifi",false).putBoolean("charging",false).commit();
            job=Pipeline.jobInfo(c,settings);assertThat(job.getNetworkType()==android.app.job.JobInfo.NETWORK_TYPE_ANY && !job.isRequireCharging(),"Mobile-data settings ignored");
        }finally{settings.prefs.edit().putBoolean("wifi",wifi).putBoolean("charging",charging).commit();}
        try{
            String sample="sk-test-this-is-not-a-real-api-key";settings.saveKey(sample);
            assertThat(settings.apiKey().equals(sample),"Keystore encrypted key roundtrip failed");
            assertThat(!settings.prefs.getString("keyEncrypted","").contains(sample),"API key stored as plaintext");
            settings.saveKey("");assertThat(!settings.hasKey(),"Key deletion failed");
        }finally{android.content.SharedPreferences.Editor edit=settings.prefs.edit();if(previousKey==null)edit.remove("keyEncrypted").remove("keyIv");else edit.putString("keyEncrypted",previousKey).putString("keyIv",previousIv);edit.commit();}

        JSONObject response=new JSONObject("{\"segments\":[{\"speaker\":\"A\",\"start\":0,\"end\":1,\"text\":\"Hola\"},{\"speaker\":\"B\",\"start\":1,\"end\":2,\"text\":\"Buen día\"},{\"speaker\":\"A\",\"start\":2,\"end\":3,\"text\":\"Seguimos\"}]}");
        Transcript transcript=Transcript.fromParts(Collections.singletonList(response),Collections.singletonList(0d));
        assertThat(transcript.speakers().get("A").equals("Persona 1") && transcript.speakers().get("B").equals("Persona 2"),"Default speaker labels incorrect");
        transcript.data.put("demo",true);transcript.save(c,r.id);FilesStore.update(c,r.id,s->s.put("demo",true));
        Map<String,String> names=new LinkedHashMap<>();names.put("A","Konra");names.put("B","Invitada");Transcript.rename(c,r.id,names);
        Transcript loaded=Transcript.load(c,r.id);String text=loaded.text(r);
        assertThat(text.contains("Konra: Hola") && text.contains("Konra: Seguimos") && text.contains("Invitada: Buen día"),"Speaker rename not applied to all turns");
        assertThat(loaded.segments().getJSONObject(0).getString("speaker").equals("A"),"Speaker identity lost after naming");
        Transcript multipart=Transcript.fromParts(Arrays.asList(response,response),Arrays.asList(0d,30d));
        assertThat(multipart.speakers().size()==4,"Speakers from separate blocks falsely merged");
        assertThat(multipart.segments().getJSONObject(3).getDouble("start")==30d,"Chunk timestamp offset lost");

        HttpApi fakeOpenAi=new HttpApi(){@Override Response request(String method,String url,String token,String contentType,Body body,Map<String,String> headers)throws Exception{
            assertThat(url.equals("https://api.openai.com/v1/audio/transcriptions"),"Unexpected transcription destination");
            ByteArrayOutputStream out=new ByteArrayOutputStream();body.write(out);assertThat(out.size()==body.length(),"Multipart content length invalid");
            String payload=out.toString(StandardCharsets.ISO_8859_1.name());
            assertThat(payload.contains("gpt-4o-transcribe-diarize") && payload.contains("diarized_json") && payload.contains("chunking_strategy"),"Diarization contract missing");
            assertThat(payload.contains("name=\"language\"\r\n\r\nes"),"Language setting ignored");
            return new Response(200,response.toString(),null);
        }};
        new OpenAiClient(fakeOpenAi).transcribe(r.audio(c),"test-token","es");
        boolean denied=false;try{HttpApi.require(new HttpApi.Response(401,"",null),"OpenAI");}catch(HttpApi.UserAction e){denied=true;}assertThat(denied,"Invalid key did not require user action");
        boolean quota=false;try{HttpApi.require(new HttpApi.Response(429,"insufficient_quota",null),"OpenAI");}catch(HttpApi.UserAction e){quota=true;}assertThat(quota,"Exhausted quota caused automatic retry");
        boolean transientError=false;try{HttpApi.require(new HttpApi.Response(503,"",null),"OpenAI");}catch(IOException e){transientError=true;}assertThat(transientError,"Server failure is not retriable");

        List<String> calls=new ArrayList<>();HttpApi fakeDrive=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{
            calls.add(method+" "+url);
            if(method.equals("GET"))return new Response(404,"{}",null);
            if(method.equals("POST")){ByteArrayOutputStream out=new ByteArrayOutputStream();body.write(out);JSONObject metadata=new JSONObject(out.toString("UTF-8"));assertThat(metadata.getString("id").equals("stable-id"),"Stable remote id missing");assertThat(metadata.getJSONArray("parents").getString(0).equals("folder-id"),"Drive parent folder ignored");return new Response(200,"{}","https://www.googleapis.com/upload/drive/v3/files?upload_id=test");}
            assertThat(method.equals("PUT"),"Unexpected Drive method");return new Response(200,"{\"id\":\"stable-id\"}",null);
        }};
        new DriveClient(fakeDrive,"test-token").upload("stable-id","test.txt","folder-id","text/plain",HttpApi.bytes("texto".getBytes(StandardCharsets.UTF_8)));
        assertThat(calls.size()==3 && calls.get(0).contains("stable-id"),"Drive idempotency preflight missing");
        List<AudioParts.Part> parts=AudioParts.prepare(c,r,new HttpApi(),1,1000);double previous=-1;
        assertThat(parts.size()>1,"Large recording split path not exercised");
        for(AudioParts.Part part:parts){assertThat(part.offset>previous && part.file.length()>0,"Audio part missing or out of order");previous=part.offset;try(android.media.MediaMetadataRetriever m=new android.media.MediaMetadataRetriever()){m.setDataSource(part.file.getPath());assertThat(m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!=null,"Split M4A invalid");}part.file.delete();}
    }
}
