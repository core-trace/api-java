package school.sptech.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import school.sptech.model.Ocorrencia;
import school.sptech.docker.LimiteDao;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ProcessadorJson {

    /**
     * Registra uma ocorrência no lote.
     *
     * Caso já exista uma ocorrência para a mesma
     * máquina, recurso e métrica, mantém a maior gravidade.
     *
     * CRITICO > ATENCAO.
     */
    private static void registrarOcorrencia(
            Map<String, Ocorrencia> ocorrencias,
            Ocorrencia nova
    ) {

        // Monta uma identificação para a ocorrência.
        // Incluímos campoOrigem para distinguir leituras
        // diferentes que compartilham o mesmo nome de métrica.
        String chave = String.join("|",
                nova.mac(),
                nova.recurso(),
                nova.metrica(),
                nova.campoOrigem() == null ? "" : nova.campoOrigem()
        );

        Ocorrencia anterior = ocorrencias.get(chave);

        // Primeira ocorrência desse recurso e métrica.
        if (anterior == null) {
            ocorrencias.put(chave, nova);
            return;
        }

        // Crítico sempre prevalece sobre atenção.
        if (nova.nivel().equals("CRITICO")
                && anterior.nivel().equals("ATENCAO")) {

            ocorrencias.put(chave, nova);
            return;
        }

        // Se ambas possuem a mesma gravidade,
        // mantém a última ocorrência processada.
        if (nova.nivel().equals(anterior.nivel())) {
            ocorrencias.put(chave, nova);
        }
    }

    /**
     * Encaminha os arrays principais do JSON para leitura.
     *
     * parâmetro tipo pode ser um Array de: metricas, processos ou containers.
     */
    public static int processarRegistros(
            JsonParser parser,
            String tipo,
            String mac,
            LimiteDao dao,
            Map<String, LimiteDao.Limite> cacheLimites,
            Set<String> metricasConsultadas,
            Map<String, Ocorrencia> alertasDoLote
    ) throws IOException {

        return percorrerArray(
                parser,
                tipo,
                null,
                mac,
                dao,
                cacheLimites,
                metricasConsultadas,
                alertasDoLote
        );
    }

    /**
     * Percorre os elementos de um array JSON.
     *
     * Cada elemento pode ser um objeto, outro array
     * ou um valor simples.
     *
     * O método utiliza recursividade para permitir
     * a leitura de arrays aninhados.
     */
    private static int percorrerArray(
            JsonParser parser,
            String caminho,
            String recurso,
            String mac,
            LimiteDao dao,
            Map<String, LimiteDao.Limite> cacheLimites,
            Set<String> metricasConsultadas,
            Map<String, Ocorrencia> alertasDoLote
    ) throws IOException {

        // Posição atual dentro do array.
        int indice = 0;

        // Quantidade de leituras válidas encontradas.
        int total = 0;

        JsonToken token;

        // Percorre até encontrar o fechamento do array: ]
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {

            if (token == null)
                throw new IOException("Array JSON incompleto");

            // Monta o caminho do elemento.
            // Exemplo: metricas[0], metricas[1]...
            String caminhoItem = caminho + "[" + indice + "]";

            if (token == JsonToken.START_OBJECT) {

                // Encontramos um objeto dentro do array.
                // Encaminhamos sua leitura ao percorrerObjeto().
                // Cada elemento herda o identificador
                // do recurso ao qual o array pertence
                total += percorrerObjeto(
                        parser,
                        caminhoItem,
                        recurso,
                        mac,
                        dao,
                        cacheLimites,
                        metricasConsultadas,
                        alertasDoLote
                );

            } else if (token == JsonToken.START_ARRAY) {

                // Encontramos outro array dentro do array atual.
                // O método chama a si mesmo: recursividade.
                // Repassa o identificador ao array interno
                total += percorrerArray(
                        parser,
                        caminhoItem,
                        recurso,
                        mac,
                        dao,
                        cacheLimites,
                        metricasConsultadas,
                        alertasDoLote
                );
            }

            // Avança o índice do elemento.
            indice++;
        }

        return total;
    }

    /**
     * Percorre os campos de um objeto JSON.
     *
     * Se encontrar objetos ou arrays internos, chama
     * os respectivos métodos recursivamente.
     *
     * Quando encontra uma leitura válida, encaminha
     * seus dados para avaliarMetrica().
     */
    private static int percorrerObjeto(
            JsonParser parser,
            String caminho,
            String recurso,
            String mac,
            LimiteDao dao,
            Map<String, LimiteDao.Limite> cacheLimites,
            Set<String> metricasConsultadas,
            Map<String, Ocorrencia> alertasDoLote
    ) throws IOException {

        // Informações que caracterizam uma leitura de métrica.
        // Cada chamada recursiva possui suas próprias variáveis.
        String metrica = null;
        String campoOrigem = null;
        String status = null;
        Double valor = null;

        // Informações utilizadas para identificar
        // CPUs lógicas, discos, redes e processos.
        String indice = null;
        String nome = null;
        String dispositivo = null;
        String montagem = null;
        String pid = null;

        int total = 0;
        JsonToken token;

        // Percorre os campos até encontrar o fechamento: }
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {

            if (token == null)
                throw new IOException("Objeto JSON inválido");

            // Nome do campo que estamos analisando.
            String campo = parser.currentName();

            // Avança para o valor desse campo.
            token = parser.nextToken();

            if (token == null)
                throw new IOException("Objeto JSON incompleto");

            // Antes de entrar em um objeto interno,
            // verifica se já identificamos o recurso pai.
            String recursoAtual = identificarRecurso(
                    caminho, recurso, indice, nome,
                    dispositivo, montagem, pid
            );

            if (token == JsonToken.START_OBJECT) {

                // Encontrou um objeto dentro do objeto atual.
                // Faz outra chamada ao mesmo método.
                total += percorrerObjeto(
                        parser,
                        caminho + "." + campo,
                        recursoAtual,
                        mac,
                        dao,
                        cacheLimites,
                        metricasConsultadas,
                        alertasDoLote
                );

                // O objeto interno já foi percorrido.
                // Continuamos com o próximo campo do objeto pai.
                continue;
            }

            if (token == JsonToken.START_ARRAY) {

                // Encontrou um array dentro do objeto.
                // Encaminha sua leitura para percorrerArray().
                total += percorrerArray(
                        parser,
                        caminho + "." + campo,
                        recursoAtual,
                        mac,
                        dao,
                        cacheLimites,
                        metricasConsultadas,
                        alertasDoLote
                );

                continue;
            }

            // Se não for objeto nem array, verifica
            // se é um dos campos necessários à leitura.
            switch (campo) {

                case "metrica" ->
                        metrica = parser.getValueAsString();

                case "campo_origem" ->
                        campoOrigem = parser.getValueAsString();

                case "status" ->
                        status = parser.getValueAsString();

                case "valor" -> {

                    // Apenas valores numéricos podem ser
                    // comparados com os limites atuais.
                    if (token.isNumeric())
                        valor = parser.getDoubleValue();
                }

                // Identificadores de recursos.
                case "indice" ->
                        indice = parser.getValueAsString();

                case "nome" ->
                        nome = parser.getValueAsString();

                case "dispositivo" ->
                        dispositivo = parser.getValueAsString();

                case "montagem" ->
                        montagem = parser.getValueAsString();

                case "pid" ->
                        pid = parser.getValueAsString();
            }
        }

        // A avaliação só acontece depois que terminamos
        // de percorrer os campos do objeto atual.
        if ("ok".equals(status)
                && metrica != null
                && !metrica.isBlank()
                && valor != null
                && Double.isFinite(valor)) {

            // Depois de ler o objeto completo, verifica
            // se ele possui um identificador mais específico.
            // Isso é importante para CPUs lógicas, porque
            // o campo "indice" pode aparecer após "valor".
            String recursoIdentificado = identificarRecurso(
                    caminho, recurso, indice, nome,
                    dispositivo, montagem, pid
            );

            // A leitura passou nas validações.
            // Agora podemos consultar e comparar seus limites.
            avaliarMetrica(
                    mac,
                    metrica,
                    campoOrigem,
                    valor,
                    recursoIdentificado,
                    dao,
                    cacheLimites,
                    metricasConsultadas,
                    alertasDoLote
            );

            total++;
        }

        return total;
    }

    /**
     * Consulta os limites de uma métrica e verifica
     * se seu valor representa atenção ou crítico.
     *
     * A leitura já deve ter sido validada antes
     * de chegar a este método.
     */
    private static void avaliarMetrica(
            String mac,
            String metrica,
            String campoOrigem,
            double valor,
            String recurso,
            LimiteDao dao,
            Map<String, LimiteDao.Limite> cacheLimites,
            Set<String> metricasConsultadas,
            Map<String, Ocorrencia> alertasDoLote
    ) throws IOException {

        // Consulta os limites apenas uma vez para cada métrica
        // encontrada no arquivo.
        if (!metricasConsultadas.contains(metrica)) {
            try {
                Map<String, LimiteDao.Limite> encontrados =
                        dao.buscarPorMac(mac, List.of(metrica));

                // O DAO retorna as chaves em minúsculas.
                String chaveMetrica = metrica.toLowerCase(Locale.ROOT);

                LimiteDao.Limite limite = encontrados.get(chaveMetrica);

                // Armazena somente as métricas que possuem limites.
                if (limite != null) {
                    cacheLimites.put(metrica, limite);
                }

                // Registra que essa métrica já foi consultada,
                // mesmo quando não existe limite no banco.
                metricasConsultadas.add(metrica);

            } catch (SQLException e) {
                throw new IOException(
                        "Erro ao buscar limites para " + metrica, e
                );
            }
        }

        // Recupera a configuração já encontrada.
        LimiteDao.Limite limite = cacheLimites.get(metrica);

        // Métrica sem limite configurado não gera alerta.
        if (limite == null) {
            System.out.printf(
                    "[SEM LIMITE] MAC: %s | Métrica: %s%n",
                    mac, metrica);

            return;
        }

        // Mostra que o Java encontrou a configuração da métrica
        // no MySQL e está prestes a realizar a comparação.
        System.out.printf(
                "[COMPARANDO] Recurso: %s | Métrica: %s | " +
                        "Valor: %.2f | Operador 1: %s | Operador 2: %s%n",
                recurso, metrica, valor,
                limite.operador(), limite.operador2()
        );

        // Guarda a gravidade e a condição que gerou o alerta.
        String nivel;
        Double limiteAtingido;
        String operadorAplicado;

        // Verifica as condições críticas dos dois operadores.
        // O segundo só é avaliado se houver operador configurado.
        boolean critico1 = atingiuLimite(
                valor, limite.critico(), limite.operador()
        );

        boolean critico2 = limite.operador2() != null
                && !limite.operador2().isBlank()
                && atingiuLimite(
                valor, limite.critico2(), limite.operador2()
        );

        // Faz o mesmo para as condições de atenção.
        boolean atencao1 = atingiuLimite(
                valor, limite.atencao(), limite.operador()
        );

        boolean atencao2 = limite.operador2() != null
                && !limite.operador2().isBlank()
                && atingiuLimite(
                valor, limite.atencao2(), limite.operador2()
        );

        // Prioridade 1: qualquer condição crítica.
        // Isso impede que uma atenção prevaleça sobre um crítico.
        if (critico1 || critico2) {

            nivel = "CRITICO";

            // Identifica qual condição crítica foi satisfeita.
            if (critico1) {
                limiteAtingido = limite.critico();
                operadorAplicado = limite.operador();
            } else {
                limiteAtingido = limite.critico2();
                operadorAplicado = limite.operador2();
            }

            // Prioridade 2: atenção, somente quando não houve crítico.
        } else if (atencao1 || atencao2) {

            nivel = "ATENCAO";

            if (atencao1) {
                limiteAtingido = limite.atencao();
                operadorAplicado = limite.operador();
            } else {
                limiteAtingido = limite.atencao2();
                operadorAplicado = limite.operador2();
            }

        } else return;

        // Cria uma ocorrência com o resultado da comparação.
        Ocorrencia ocorrencia = new Ocorrencia(
                mac,
                recurso,
                metrica,
                campoOrigem,
                valor,
                nivel,
                operadorAplicado,
                limiteAtingido
        );

        // Encaminha para o agrupamento.
        // Caso já exista uma ocorrência equivalente,
        // será aplicada a prioridade de gravidade.
        registrarOcorrencia(alertasDoLote, ocorrencia);
    }

    /**
     * Identifica o recurso físico ou lógico associado
     * a determinado objeto do JSON.
     *
     * O identificador herdado vem do objeto pai.
     * Exemplo: a métrica de ocupação herda "/dev/sda1"
     * do objeto que representa o volume.
     */
    private static String identificarRecurso(
            String caminho,
            String herdado,
            String indice,
            String nome,
            String dispositivo,
            String montagem,
            String pid
    ) {

        // Cada item de metricas representa uma captura
        // da mesma máquina em um instante diferente.
        //
        // Não usamos metricas[0] ou metricas[1] como
        // identificação do recurso, pois isso impediria
        // agrupar as leituras da CPU total e outras métricas.
        if (caminho.matches("^metricas\\[\\d+\\]$")) {
            return "Máquina";
        }

        // CPU lógica: identificada pelo índice.
        if (caminho.contains(".logicas.leituras[")
                && indice != null) {
            return "CPU lógica " + indice;
        }

        // Volume de disco: identificamos o dispositivo
        // e também o ponto de montagem, se disponível.
        if (caminho.contains(".disco.volumes[")
                && dispositivo != null) {
            return "Volume " + dispositivo
                    + (montagem != null ? " (" + montagem + ")" : "");
        }

        // Disco utilizado nas capturas de I/O.
        if (caminho.contains(".disco.io[")
                && dispositivo != null) {
            return "Disco " + dispositivo;
        }

        // Interface de rede.
        if (caminho.contains(".rede.interfaces[")
                && nome != null) {
            return "Interface " + nome;
        }

        // Processo individual.
        if (caminho.startsWith("processos[")
                && !caminho.contains(".")
                && nome != null) {
            return "Processo " + nome
                    + (pid != null ? " (PID " + pid + ")" : "");
        }

        // Contêiner individual.
        if (caminho.startsWith("containers[")
                && !caminho.contains(".")
                && nome != null) {
            return "Contêiner " + nome;
        }

        // Se o objeto não possui identificador próprio,
        // mantém o identificador recebido do pai.
        return herdado != null ? herdado : caminho;
    }

    /** Avalia a métrica usando o operador configurado em componentes_perfil.operador. */
    private static boolean atingiuLimite(double valor, Double limite, String operador) {
        if (limite == null) {
            return false;
        }

        String op = (operador == null || operador.isBlank()) ? ">" : operador.trim();
        return switch (op) {
            case ">" -> valor > limite;
            case ">=" -> valor >= limite;
            case "<" -> valor < limite;
            case "<=" -> valor <= limite;
            case "=", "==" -> Double.compare(valor, limite) == 0;
            case "!=", "<>" -> Double.compare(valor, limite) != 0;
            default -> {
                System.err.printf("Operador '%s' não suportado; limite ignorado.%n", op);
                yield false;
            }
        };
    }
}
