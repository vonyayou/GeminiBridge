package me.vonyayou.geminibridge.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({"/", "/dashboard", "/playground", "/models", "/history", "/providers", "/settings"})
    String app() { return "forward:/index.html"; }
}
