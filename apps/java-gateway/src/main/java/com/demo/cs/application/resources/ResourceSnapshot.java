package com.demo.cs.application.resources;
import com.fasterxml.jackson.databind.JsonNode;
public record ResourceSnapshot(String id,String code,String kind,String name,int version,JsonNode config,String credentialRef,int observedPublishedVersion) {
 public ResourceSnapshot(String id,String code,String kind,String name,int version,JsonNode config,String credentialRef){this(id,code,kind,name,version,config,credentialRef,version);}
 public ResourceSnapshot { config=config.deepCopy(); }
}
