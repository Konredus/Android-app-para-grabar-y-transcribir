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

        HttpApi custom=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{
            assertThat(url.equals("https://example.com/v1/audio/transcriptions"),"Custom endpoint ignored");ByteArrayOutputStream out=new ByteArrayOutputStream();body.write(out);String payload=out.toString("ISO-8859-1");assertThat(!payload.contains("diarized_json")&&!payload.contains("chunking_strategy"),"Plain model received diarization options");return new Response(200,"{\"text\":\"Texto de prueba\"}",null);
        }};
        JSONObject plain=new OpenAiClient(custom).transcribe(r.audio(c),new ProviderConfig("custom","https://example.com/v1/","test-model","test-token",false),"");
        Transcript plainTranscript=Transcript.fromParts(Collections.singletonList(plain),Collections.singletonList(0d));assertThat(!plainTranscript.data.getBoolean("diarized")&&plainTranscript.speakers().containsValue("Texto"),"Plain transcript fabricated speakers");
        boolean unsafe=false;try{new ProviderConfig("custom","http://example.com/v1","m","",false);}catch(IllegalArgumentException e){unsafe=true;}assertThat(unsafe,"HTTP endpoint allowed");
        File crop=new File(c.getCacheDir(),"test-crop.m4a");long originalSize=r.audio(c).length();AudioConvert.convert(r.audio(c),crop,500,2000,new HttpApi());assertThat(Math.abs(AudioConvert.duration(crop)-1500)<300,"AAC crop duration wrong");assertThat(r.audio(c).length()==originalSize,"Cropping modified original");crop.delete();
        File wav=new File(c.getCacheDir(),"test-import.wav");int rate=16000,samples=rate*3;java.nio.ByteBuffer pcm=java.nio.ByteBuffer.allocate(44+samples*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);pcm.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+samples*2).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples*2);for(int i=0;i<samples;i++)pcm.putShort((short)(Math.sin(i*2*Math.PI*440/rate)*8000));try(FileOutputStream out=new FileOutputStream(wav)){out.write(pcm.array());}
        AudioConvert.convert(wav,crop,1000,2500,new HttpApi());assertThat(Math.abs(AudioConvert.duration(crop)-1500)<300,"WAV conversion duration wrong");wav.delete();crop.delete();
        Diagnostics.event("test_event",r.id,"action","test_button","api_key","DO-NOT-EXPORT-THIS-SECRET");String report=new String(java.nio.file.Files.readAllBytes(Diagnostics.export(c).toPath()),StandardCharsets.UTF_8);assertThat(report.contains("test_event")&&!report.contains("DO-NOT-EXPORT-THIS-SECRET")&&!report.contains("Konra: Hola"),"Diagnostics leaked data or omitted events");
        android.net.Uri reportUri=android.net.Uri.parse("content://cl.vozlocal.app.audio/support.txt");try(android.os.ParcelFileDescriptor descriptor=c.getContentResolver().openFileDescriptor(reportUri,"r")){assertThat(descriptor.getStatSize()>0,"Support export unavailable");}
        List<AudioParts.Part> parts=AudioParts.prepare(c,r,new HttpApi(),1,1000);double previous=-1;
        assertThat(parts.size()>1,"Large recording split path not exercised");
        for(AudioParts.Part part:parts){assertThat(part.offset>previous && part.file.length()>0,"Audio part missing or out of order");previous=part.offset;try(android.media.MediaMetadataRetriever m=new android.media.MediaMetadataRetriever()){m.setDataSource(part.file.getPath());assertThat(m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!=null,"Split M4A invalid");}part.file.delete();}
        blocksAndModels(c,r,response);
    }
    /** 0.4.2: modelo rápido, muestras de voz entre bloques, tramos, pausas y costos. */
    static void blocksAndModels(Context c,Recording r,JSONObject diarized)throws Exception{
        // gpt-transcribe: languages[] (no "language"), streaming y sin opciones de voces.
        HttpApi fast=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{
            ByteArrayOutputStream out=new ByteArrayOutputStream();body.write(out);String payload=out.toString("ISO-8859-1");
            assertThat(payload.contains("name=\"model\"\r\n\r\ngpt-transcribe\r\n"),"Fast model not sent");
            assertThat(payload.contains("name=\"languages[]\"\r\n\r\nes")&&!payload.contains("name=\"language\""),"gpt-transcribe must use languages[] only");
            assertThat(payload.contains("name=\"stream\"\r\n\r\ntrue")&&!payload.contains("diarized_json"),"Streaming/diarization options wrong for fast model");
            return new Response(200,"{\"type\":\"transcript.text.done\",\"text\":\"Hola mundo\",\"usage\":{\"type\":\"tokens\",\"input_tokens\":120,\"output_tokens\":8}}",null);
        }};
        JSONObject quick=new OpenAiClient(fast).transcribe(r.audio(c),new ProviderConfig("openai","https://api.openai.com/v1","gpt-transcribe","k",false),"es",null,chars->{});
        assertThat(quick.getJSONArray("segments").getJSONObject(0).getString("text").equals("Hola mundo")&&quick.getJSONObject("usage").getLong("input_tokens")==120,"Fast model response not parsed");
        // Diarize con muestras de voz: se envían nombres y data URLs.
        HttpApi refs=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{
            ByteArrayOutputStream out=new ByteArrayOutputStream();body.write(out);String payload=out.toString("ISO-8859-1");
            assertThat(payload.contains("name=\"known_speaker_names[]\"\r\n\r\nA")&&payload.contains("name=\"known_speaker_references[]\"\r\n\r\ndata:audio/mp4;base64,"),"Speaker references not sent");
            return new Response(200,diarized.toString(),null);
        }};
        new OpenAiClient(refs).transcribe(r.audio(c),new ProviderConfig("openai","https://api.openai.com/v1","gpt-4o-transcribe-diarize","k",true),"es",Collections.singletonList(new String[]{"A","data:audio/mp4;base64,AAAA"}),null);
        // Bloque 2 reconoce a "A" (muestra de voz) → misma persona que en el bloque 1; "C" es nueva.
        JSONObject second=new JSONObject("{\"_known\":[\"A\"],\"segments\":[{\"speaker\":\"A\",\"start\":0,\"end\":1,\"text\":\"Sigo yo\"},{\"speaker\":\"C\",\"start\":1,\"end\":2,\"text\":\"Hola\"}]}");
        Transcript merged=Transcript.fromParts(Arrays.asList(diarized,second),Arrays.asList(0d,300d));
        assertThat(merged.speakers().size()==3&&merged.segments().getJSONObject(3).getString("speaker").equals("block0:A"),"Known speaker not unified across blocks");
        // Tramo sin recodificar y búsqueda de pausa dentro del rango.
        File range=new File(c.getCacheDir(),"test-range.m4a");AudioParts.remuxRange(r.audio(c),range,500,2000,new HttpApi());
        assertThat(Math.abs(AudioConvert.duration(range)-1500)<400,"Range remux duration wrong");range.delete();
        long q=AudioParts.quietest(r.audio(c),1500,1000,new HttpApi());assertThat(q>=400&&q<=2600,"Quiet point outside search window");
        // Costos y selección de modelo según la elección de voces.
        assertThat(Math.abs(Pricing.estimate("gpt-transcribe",60_000)-0.0045)<1e-9&&Pricing.estimate("custom-model",60_000)<0,"Pricing estimate wrong");
        assertThat(Pricing.usd(0.0123).equals("US$0,012"),"Currency format wrong");
        Settings settings=new Settings(c);String provider=settings.provider();
        try{settings.prefs.edit().putString("provider","openai").remove("openaiTextModel").commit();
            assertThat(settings.config(false).model.equals("gpt-transcribe")&&!settings.config(false).speakers,"Default text model should be gpt-transcribe");
            assertThat(settings.config(true).model.equals("gpt-4o-transcribe-diarize")&&settings.config(true).speakers,"Speaker model wrong");
        }finally{settings.prefs.edit().putString("provider",provider).commit();}
    }
}
