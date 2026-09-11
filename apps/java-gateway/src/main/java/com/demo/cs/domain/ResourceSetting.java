package com.demo.cs.domain;
import jakarta.persistence.*;
@Entity @Table(name="cfg_setting")
public class ResourceSetting {
 @Id public String id;
 public String settingValue;
}
