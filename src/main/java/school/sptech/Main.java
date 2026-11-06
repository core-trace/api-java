package school.sptech;

import school.sptech.service.ProcessamentoService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CoreTrace: lê um ou mais CSVs do S3, procura os limites das métricas
 * configuradas para cada servidor no MySQL e cria ticket no Jira para alertas críticos.
 */
public class Main {

    /**
     * Representa uma ocorrência identificada
     * durante a análise das métricas.
     */
    public record Ocorrencia(
            String mac,
            String recurso,
            String metrica,
            String campoOrigem,
            double valor,
            String nivel,
            String operador,
            double limite
    ) {}

    public static void main(String[] args) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleWithFixedDelay(() -> {
            try {
                System.out.println("Verificando novos JSONs no S3...");

                ProcessamentoService processamentoService = new ProcessamentoService();
                processamentoService.executarProcessamento();

                System.out.println("\nProcessamento concluído em " + java.time.LocalDateTime.now() + ". Próxima verificação em 5 minutos.");
            } catch (Exception e) {
                System.out.println("Erro: " + e.getMessage());
            }
        }, 0, 5, TimeUnit.MINUTES);
    }
}
