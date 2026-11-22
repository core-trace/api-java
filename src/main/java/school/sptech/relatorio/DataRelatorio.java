package school.sptech.relatorio;

import school.sptech.config.Env;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

// Calcula a data usada no nome do arquivo do relatorio no S3. Respeita a mesma variavel DATA_DEMO_FIXA que o website usa 

public final class DataRelatorio {

    private static final DateTimeFormatter FORMATO_CHAVE = DateTimeFormatter.ISO_LOCAL_DATE;

    private DataRelatorio() {
    }

    public static LocalDate hojeNoCenario() {
        String dataFixa = Env.get("DATA_DEMO_FIXA", "").trim();
        if (!dataFixa.isEmpty()) {
            return LocalDate.parse(dataFixa);
        }
        return LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    }

    public static String formatarParaChave(LocalDate data) {
        return data.format(FORMATO_CHAVE);
    }
}
