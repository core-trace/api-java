package school.sptech.relatorio;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import school.sptech.config.Env;
import school.sptech.dto.ResumoGeralDto;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

//Pega o resumo quem vem em json que o website expoe em GET /geral/resumo/**

public class ResumoGeralClient {

    public ResumoGeralDto buscarResumo() throws IOException, InterruptedException {
        String baseUrl = Env.get("WEBSITE_BASE_URL", "http://localhost:3333");

        // Criacao do cliente HTTP
        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();
        // O relatorio so le uma parte dos campos do JSON (ver ResumoGeralDto) 
        // sem isso, o Jackson falha ao encontrar "sinaisExternos"/"evolucao".
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Requisicao GET
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/geral/resumo"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException(
                    "GET " + baseUrl + "/geral/resumo devolveu status " + response.statusCode()
            );
        }

        // deserializa o JSON em um objeto Java
        return mapper.readValue(response.body(), ResumoGeralDto.class);
    }
}
