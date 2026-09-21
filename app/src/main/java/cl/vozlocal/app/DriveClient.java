package cl.vozlocal.app;

import android.accounts.Account;
import android.content.Context;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import org.json.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class DriveClient {
    static final String SCOPE="https://www.googleapis.com/auth/drive.file";
    final HttpApi http;final String token;
    DriveClient(HttpApi http,String token){this.http=http;this.token=token;}
    static AuthorizationRequest request(Context c){
        AuthorizationRequest.Builder builder=AuthorizationRequest.builder().setRequestedScopes(Collections.singletonList(new Scope(SCOPE)));
        String email=new Settings(c).prefs.getString("driveEmail","");if(!email.isEmpty())builder.setAccount(new Account(email,"com.google"));return builder.build();
    }
    static String token(Context c)throws Exception{
        AuthorizationResult result;
        try{result=Tasks.await(Identity.getAuthorizationClient(c).authorize(request(c)),45,TimeUnit.SECONDS);}
        catch(Exception e){throw new HttpApi.UserAction("Abre Configuración y vuelve a vincular Google Drive.");}
        if(result.hasResolution() || result.getAccessToken()==null)throw new HttpApi.UserAction("Google necesita tu autorización. Vuelve a vincular Drive en Configuración.");
        return result.getAccessToken();
    }
    JSONObject about()throws Exception{return get("https://www.googleapis.com/drive/v3/about?fields=user(displayName,emailAddress)");}
    JSONObject get(String url)throws Exception{HttpApi.Response response=http.request("GET",url,token,null,null,null);HttpApi.require(response,"Google Drive");return response.json();}
    String generateId()throws Exception{return get("https://www.googleapis.com/drive/v3/files/generateIds?count=1&space=drive&type=files").getJSONArray("ids").getString(0);}
    synchronized String ensureFolder(Context c)throws Exception{
        Settings settings=new Settings(c);String id=settings.folderId();
        if(id.isEmpty()){id=generateId();settings.prefs.edit().putString("folderId",id).commit();}
        HttpApi.Response existing=http.request("GET","https://www.googleapis.com/drive/v3/files/"+id+"?fields=id,trashed,mimeType",token,null,null,null);
        if(existing.code==200){if(existing.json().optBoolean("trashed"))throw new HttpApi.UserAction("La carpeta de Drive está en la papelera. Elige otra en Configuración.");return id;}
        if(existing.code!=404)HttpApi.require(existing,"Google Drive");
        JSONObject folder=new JSONObject().put("id",id).put("name",settings.prefs.getString("folderName","Voz local")).put("mimeType","application/vnd.google-apps.folder");
        HttpApi.Response response=http.request("POST","https://www.googleapis.com/drive/v3/files?fields=id",token,"application/json; charset=UTF-8",HttpApi.json(folder),null);
        if(response.code!=409)HttpApi.require(response,"Google Drive");return id;
    }
    JSONArray folders()throws Exception{
        String query=java.net.URLEncoder.encode("mimeType = 'application/vnd.google-apps.folder' and trashed = false","UTF-8");
        return get("https://www.googleapis.com/drive/v3/files?q="+query+"&fields=files(id,name)&pageSize=100").getJSONArray("files");
    }
    void rename(String id,String name)throws Exception{HttpApi.Response response=http.request("PATCH","https://www.googleapis.com/drive/v3/files/"+id,token,"application/json",HttpApi.json(new JSONObject().put("name",name)),null);HttpApi.require(response,"Google Drive");}
    void upload(String id,String name,String folder,String mime,HttpApi.Body data)throws Exception{
        HttpApi.Response existing=http.request("GET","https://www.googleapis.com/drive/v3/files/"+id+"?fields=id,trashed",token,null,null,null);
        boolean create=existing.code==404;
        if(!create){HttpApi.require(existing,"Google Drive");if(existing.json().optBoolean("trashed"))throw new HttpApi.UserAction("El archivo de Drive está en la papelera. Restáuralo antes de sincronizar.");}
        JSONObject metadata=new JSONObject().put("name",name);
        if(create)metadata.put("id",id).put("parents",new JSONArray().put(folder));
        Map<String,String> headers=new HashMap<>();headers.put("X-Upload-Content-Type",mime);headers.put("X-Upload-Content-Length",String.valueOf(data.length()));
        HttpApi.Response init=http.request(create?"POST":"PATCH","https://www.googleapis.com/upload/drive/v3/files"+(create?"":"/"+id)+"?uploadType=resumable",token,"application/json; charset=UTF-8",HttpApi.json(metadata),headers);
        HttpApi.require(init,"Google Drive");
        if(init.location==null || !"https".equals(new java.net.URI(init.location).getScheme()) || !new java.net.URI(init.location).getHost().endsWith(".googleapis.com"))throw new java.io.IOException("Google devolvió una dirección de carga inválida.");
        HttpApi.Response result=http.request("PUT",init.location,token,mime,data,null);HttpApi.require(result,"Google Drive");
    }
}
