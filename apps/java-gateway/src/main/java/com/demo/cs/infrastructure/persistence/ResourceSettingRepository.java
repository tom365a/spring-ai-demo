package com.demo.cs.infrastructure.persistence;
import com.demo.cs.domain.ResourceSetting;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ResourceSettingRepository extends JpaRepository<ResourceSetting,String> {}
