package me.vonyayou.geminibridge.config;

import jakarta.persistence.*;

@Entity
@Table(name = "app_settings")
public class SettingEntity {
    @Id public Long id = 1L;
    @Column(nullable = false, length = 8192) public String json;
    protected SettingEntity() {}
    public SettingEntity(String json) { this.json = json; }
}
