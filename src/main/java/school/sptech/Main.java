package school.sptech;

import school.sptech.relatorio.RelatorioService;
import school.sptech.service.ProcessamentoService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CoreTrace: lê um ou mais CSVs do S3, procura os limites das métricas
 * configuradas para cada servidor no MySQL e cria ticket no Jira para alertas críticos.
 * Também gera, a cada hora, o relatório geral em PDF e sobe pro S3.
 */
public class Main {
    public static void main(String[] args) {
        // 2 threads: a geração do relatório (HTTP + render de PDF) não pode atrasar a checagem de alertas, que é mais urgente e roda a cada 5 min.
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

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

        scheduler.scheduleAtFixedRate(() -> {
            try {
                System.out.println("Gerando relatório geral em PDF...");

                new RelatorioService().executar();
            } catch (Exception e) {
                System.out.println("Erro ao gerar relatório: " + e.getMessage());
            }
        }, 0, 1, TimeUnit.HOURS);
    }
}
