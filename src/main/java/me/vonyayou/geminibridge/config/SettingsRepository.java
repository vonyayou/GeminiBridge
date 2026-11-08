package me.vonyayou.geminibridge.config;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingsRepository extends JpaRepository<SettingEntity, Long> {}
