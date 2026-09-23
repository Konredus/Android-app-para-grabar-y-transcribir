package cl.vozlocal.app;

import java.net.URI;

final class ProviderConfig {
    final String provider,base,model,key;final boolean speakers;
    ProviderConfig(String provider,String base,String model,String key,boolean speakers)throws Exception{
        URI uri=new URI(base.trim());
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)throw new IllegalArgumentException("Usa una dirección HTTPS sin usuario, parámetros ni fragmentos.");
        if(!model.matches("[A-Za-z0-9_.:/-]{1,120}"))throw new IllegalArgumentException("Nombre de modelo inválido.");
        this.provider=provider;this.base=base.trim().replaceAll("/+$","");this.model=model;this.key=key;this.speakers=speakers;
    }
    String fingerprint()throws Exception{return PipelineJob.hash((provider+"|"+base+"|"+model+"|"+speakers).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
