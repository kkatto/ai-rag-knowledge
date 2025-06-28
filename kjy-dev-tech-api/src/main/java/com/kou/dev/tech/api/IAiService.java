package com.kou.dev.tech.api;

import org.springframework.ai.chat.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * @author KouJY
 * @description
 * @create 2025-06-27 8:22
 */
public interface IAiService {

    ChatResponse generate(String model, String message);

    Flux<ChatResponse> generateStream(String model, String message);

    Flux<ChatResponse> generateStreamRag(String model, String ragTag, String message);
}
