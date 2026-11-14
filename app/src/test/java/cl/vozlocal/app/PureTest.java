package cl.vozlocal.app;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 0.9.6: pruebas de lógica pura que corren en la computadora (y en GitHub) en segundos, sin emulador. Las de pantalla,
 * audio y red siguen en androidTest (RecorderSmokeTest y las clases *Checks).
 */
public class PureTest {
    @Test public void retryAfterSecondsDatesAndCap(){
        assertEquals(120_000,HttpApi.retryAfter("120",0));
        assertEquals(0,HttpApi.retryAfter(null,0));
        assertEquals(0,HttpApi.retryAfter("mañana",0));
        assertEquals(HttpApi.RETRY_AFTER_MAX_MS,HttpApi.retryAfter("999999",0));
        long now=java.util.Date.UTC(126,9,21,7,27,0);
        assertEquals(60_000,HttpApi.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT",now));
    }

    @Test public void openRouterWaitGrowsWithTheBlock(){
        assertEquals(Transcriber.OR_RESPONSE_MAX_MS,Transcriber.orResponseLimit(60_000));
        assertEquals(7*60_000L,Transcriber.orResponseLimit(12*60_000L));
        assertEquals(7*60_000L,Transcriber.responseLimit("openrouter",12*60_000L));
        assertEquals(13*60_000L,Transcriber.responseLimit("openai",12*60_000L));
    }

    @Test public void oneUploadAtATimeAfterARateLimit(){
        long before=Transcriber.rateLimitedAt;
        try{
            Transcriber.rateLimitedAt=0;assertEquals(Transcriber.PARALLEL,Transcriber.parallel(1_000_000));
            Transcriber.rateLimitedAt=1_000_000;assertEquals(1,Transcriber.parallel(1_000_000+60_000));
            assertEquals(Transcriber.PARALLEL,Transcriber.parallel(1_000_000+Transcriber.RATE_LIMIT_CALM_MS+1));
        }finally{Transcriber.rateLimitedAt=before;}
    }

    @Test public void silentRecordingsAreDetected()throws Exception{
        assertFalse(RecorderService.mostlySilent(null));
        assertTrue(RecorderService.mostlySilent(new JSONObject().put("level",new JSONObject().put("seconds",100).put("quiet",95))));
        assertFalse(RecorderService.mostlySilent(new JSONObject().put("level",new JSONObject().put("seconds",100).put("quiet",50))));
        assertFalse(RecorderService.mostlySilent(new JSONObject().put("level",new JSONObject().put("seconds",4).put("quiet",4))));
    }

    @Test public void answerParsingSurvivesExtraBraces(){
        assertEquals("{\"a\":\"}\"}",Notes.firstObject("texto {\"a\":\"}\"} y {más}",6));
        assertNull(Notes.firstObject("{\"a\":1",0));
    }

    @Test public void namesAndQuotesMustComeFromTheConversation(){
        String source="<transcript>\n[00:00] {S1}: Hola José, revisemos el contrato.\n</transcript>\n";
        Map<String,String> known=new LinkedHashMap<>();known.put("S1","voice:me");
        assertEquals("José",Notes.who("José",known,source));
        assertEquals("jose",Notes.fold("JOSÉ"));
        assertEquals("",Notes.who("Ricardo",known,source));
        assertEquals("S1",Notes.who("{S1}",known,source));
        assertTrue(Notes.inSource("Revisemos el contrato",source));
        assertFalse(Notes.inSource("Vendemos la casa en marzo",source));
    }

    @Test public void tagsAndTimes(){
        assertEquals(192.0,Notes.seconds("03:12"),0.001);
        assertEquals(-1.0,Notes.seconds("pronto"),0.001);
        assertEquals("[\"presupuesto\",\"equipo-de-trabajo\"]",Notes.cleanTags("#Presupuesto, equipo de trabajo, 2026").toString());
    }
}
