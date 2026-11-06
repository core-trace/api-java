# CoreTrace — documentação técnica da API Java

> **Referência do código:** versão contida no arquivo `java.zip` fornecido para esta documentação. **Escopo:** o módulo Java de processamento de capturas JSON no Amazon S3, leitura de limites no MySQL e gestão de chamados no Jira Cloud. Este guia descreve o comportamento **implementado**, não funcionalidades hipotéticas. Os exemplos de MAC, métricas, limites e chamados são didáticos; não representam dados reais do ambiente.

## 1. Visão geral

O módulo Java do **CoreTrace** automatiza a análise de capturas de infraestrutura e a abertura de chamados quando métricas ultrapassam limites associados ao perfil de um servidor. Ele executa um ciclo de verificação a cada cinco minutos **após a conclusão do ciclo anterior**, usando um agendador de uma única thread.

```text
Agente/ETL externo → Amazon S3 (capturas.json)
                         │
                         ▼
                   LoteS3Service
                encontra novos lotes
                         │
                         ▼
                 ProcessamentoService
                  abre JSON via stream
                         │
                         ▼
                    ProcessadorJson ─────→ LimiteDao ─────→ MySQL
                 extrai e avalia           consulta limites
                         │
                         ▼
                    Ocorrências
                  agrupadas por recurso
                         │
                         ▼
                    ChamadoService ──────→ Jira (API REST)
                  busca, cria ou escala
                         │
                         ▼
                   LoteS3Service
               grava marcador capturas.json.ok
```

**Responsabilidades:** a captura e o envio do JSON não são implementados neste ZIP: são etapas externas consumidas pelo módulo Java. A aplicação Java não grava limites no MySQL; ela apenas os consulta. O encerramento dos chamados também não é automático.

### Comportamentos fundamentais

- **Filtragem por servidor:** o endereço MAC (`endereco_mac`) identifica o servidor que determina quais limites do MySQL consultar.
- **Filtragem por métrica:** somente leituras com `status = "ok"`, identificador de métrica e valor numérico finito entram na avaliação.
- **Duas condições opcionais por métrica:** cada configuração pode ter dois conjuntos de operadores e limites, por exemplo `> 90` e `< 10`.
- **Precedência:** qualquer condição **CRÍTICA** satisfeita prevalece sobre uma condição de **ATENÇÃO**.
- **Agrupamento por lote:** máquina + recurso + métrica + campo de origem identificam a ocorrência. Para a mesma chave, crítico prevalece; com o mesmo nível, mantém a última leitura percorrida.
- **Controle de reprocessamento:** cada `capturas.json` concluído gera um objeto `.ok` no S3. O mesmo lote é ignorado em ciclos posteriores.
- **Controle de tickets:** uma etiqueta estável permite buscar um chamado ainda não concluído no Jira; se houver escalada, o título recebe `[CRÍTICO]` e o ticket ganha a etiqueta `coretrace-critico`.

## 2. Estrutura real do projeto

```text
api-java/
├── pom.xml
├── CONFIGURACAO.md                  # arquivo legado que ainda descreve CSV
└── src/main/java/school/sptech/
    ├── Main.java                    # inicialização, agenda e record interno
    ├── client/
    │   ├── MySqlProvider.java       # conexões JDBC
    │   └── S3Provider.java          # cliente AWS S3
    ├── config/
    │   ├── Env.java                 # .env e variáveis de ambiente
    │   └── Jira.java                # cliente HTTP da API Jira
    ├── docker/
    │   └── LimiteDao.java           # consultas SQL de limites
    ├── dto/
    │   ├── IssueFieldsDto.java
    │   ├── IssueRequestDto.java
    │   ├── IssueTypeDto.java
    │   └── ProjectDto.java
    ├── model/
    │   └── Ocorrencia.java          # record separado, não usado pelo fluxo atual
    ├── s3/
    │   └── LoteS3Service.java       # descoberta de lotes e marcadores .ok
    └── service/
        ├── ChamadoService.java      # decisões de tickets
        ├── ProcessadorJson.java     # leitura aninhada, limites e agrupamento
        └── ProcessamentoService.java # orquestração dos lotes
```

**Observação sobre `Ocorrencia`:** o ZIP contém dois records com o mesmo nome: `school.sptech.Main.Ocorrencia` e `school.sptech.model.Ocorrencia`. `ProcessadorJson`, `ProcessamentoService` e `ChamadoService` importam **`Main.Ocorrencia`**. Portanto, o record do pacote `model` existe no repositório, mas **não é o tipo usado por esse fluxo**.

## 3. Um ciclo de processamento, passo a passo

1. `Main.main()` cria um `ScheduledExecutorService` com uma thread e agenda a execução inicial imediata; cada nova execução começa cinco minutos depois de a anterior terminar.
2. `ProcessamentoService.executarProcessamento()` valida configurações, cria `LimiteDao`, `Jira` e `S3Client` e instancia `LoteS3Service`.
3. `LoteS3Service.obterChavesCaptura()` lista as chaves terminadas em `/capturas.json` dentro de `S3_PREFIX`, ou usa somente `S3_KEY` quando preenchido; ordena as chaves.
4. Para cada chave, `loteJaProcessado()` procura o correspondente `.ok` usando `headObject()`. Encontrou? O lote é ignorado.
5. Caso não haja `.ok`, `processarJson()` faz `getObject()` no S3 e passa um `InputStream` ao `JsonParser`. Não materializa todo o JSON em uma única árvore na memória.
6. O objeto raiz fornece `endereco_mac` e `lote_id`. Os arrays `metricas`, `processos` e `containers` são encaminhados a `ProcessadorJson.processarRegistros()`.
7. O processador percorre objetos e arrays recursivamente, identifica o recurso e seleciona leituras numéricas válidas. Para cada métrica, consulta `LimiteDao.buscarPorMac()` no máximo uma vez **por arquivo** graças ao cache local.
8. O processador decide entre `ATENCAO`, `CRITICO` e nenhuma ocorrência; guarda os alertas agrupados em um `LinkedHashMap`.
9. Depois da leitura, `ChamadoService.criarTicket()` consulta o Jira por etiqueta. Reutiliza um chamado aberto, escala para crítico quando necessário ou cria um novo.
10. **Somente se `processarJson()` terminar com sucesso**, `marcarLoteProcessado()` cria o objeto `.ok`. Falhas tratadas como `IOException` ou `S3Exception` impedem o marcador daquele lote.

> **Importante:** o `.ok` é gravado por arquivo, não por leitura individual. Uma falha após a criação de alguns tickets, mas antes do `.ok`, permite o reprocessamento do lote. A busca no Jira reduz esse problema, porém não oferece uma transação distribuída nem proteção absoluta contra corridas.

## 4. Contrato de entrada e exemplo de JSON

O parser espera um **objeto JSON na raiz**. Entre os campos conhecidos estão `endereco_mac`, `lote_id`, `metricas`, `processos` e `containers`. Outros campos são ignorados com `skipChildren()`.

**Exemplo didático mínimo compatível com o percurso implementado:**

```json
{
  "endereco_mac": "02:00:00:00:00:01",
  "lote_id": "lote-exemplo-001",
  "metricas": [
    {
      "cpu": {
        "logicas": {
          "leituras": [
            {
              "indice": 1,
              "uso": {
                "metrica": "cpu_percent_percpu",
                "campo_origem": "cpu_percent_percpu",
                "valor": 80.0,
                "status": "ok"
              }
            }
          ]
        }
      }
    }
  ],
  "processos": [],
  "containers": []
}
```

O percurso reconhece `metricas[0].cpu.logicas.leituras[0]`, encontra `indice = 1` e usa **`CPU lógica 1`** como recurso associado à leitura `cpu_percent_percpu = 80`. Para esse exemplo produzir um alerta, a métrica precisa estar configurada no MySQL para o MAC informado e o valor precisa ultrapassar seu limite.

**Regras efetivamente aplicadas:**

| Situação | Resultado |
|---|---|
| `status` igual a `"ok"`, `metrica` não vazia, `valor` numérico finito | Leitura contabilizada e enviada à avaliação |
| `status` diferente de `"ok"` | Ignorada como leitura válida |
| `valor` não numérico, ausente, `NaN` ou infinito | Ignorado |
| Métrica sem configuração no perfil do servidor | Conta como leitura válida, mas **não gera alerta** |
| `processos` ou `containers` sem objetos com `metrica` e `status = "ok"` | Nenhuma leitura válida desses objetos |
| Array de métricas antes de `endereco_mac` na raiz | `IOException("MAC não identificado")` |
| `lote_id` ausente | Aparece `null` no log; **não há validação final desse campo** na versão enviada |

**Atenção à ordem dos campos:** embora JSON, em geral, não dependa da ordem de propriedades, este código consulta alguns identificadores do objeto pai **antes** de descer ao objeto filho. Se `indice`, `nome` ou `dispositivo` vierem depois do campo aninhado, o recurso herdado pode não ser o esperado. O exemplo coloca `indice` antes de `uso` deliberadamente.

## 5. Referência completa das classes e métodos

As assinaturas abaixo refletem as classes Java do ZIP. Quando um método é `private`, ele é detalhado para facilitar a manutenção, mas não é uma API pública. As ligações “ver código” usam caminhos relativos, prontos para funcionar quando este guia estiver em `docs/` no próprio repositório.

### 5.1 `Main` — inicialização do aplicativo

**Arquivo:** [`Main.java`](../src/main/java/school/sptech/Main.java)  
**Papel:** ponto de entrada do JAR, agendamento e definição de `Main.Ocorrencia`.

**`public static void main(String[] args)`**

- **Entrada:** argumentos de linha de comando (não utilizados).
- **Saída:** nenhuma; mantém o agendador ativo.
- **Funcionamento:** cria `Executors.newSingleThreadScheduledExecutor()`, agenda com `scheduleWithFixedDelay(..., 0, 5, MINUTES)`, instancia um `ProcessamentoService` a cada ciclo e chama `executarProcessamento()`.
- **Exemplo:** se o ciclo iniciar às 10:00 e terminar às 10:02, a próxima execução começa por volta de **10:07**, não necessariamente 10:05.
- **Erro:** imprime mensagem em `System.out`; o próximo ciclo continua agendado.

**`public record Main.Ocorrencia(...)`**

Armazena `mac`, `recurso`, `metrica`, `campoOrigem`, `valor`, `nivel`, `operador` e `limite`. Não é um ticket; representa a condição detectada. Exemplo conceitual:

```java
new Main.Ocorrencia(
    "02:00:00:00:00:01", "CPU lógica 1", "cpu_percent_percpu",
    "cpu_percent_percpu", 80.0, "ATENCAO", ">", 70.0
);
```

### 5.2 `ProcessamentoService` — orquestração

**Arquivo:** [`ProcessamentoService.java`](../src/main/java/school/sptech/service/ProcessamentoService.java)  
**Papel:** controlar o ciclo, criar dependências, abrir cada JSON e entregar ocorrências ao Jira.

**`public void executarProcessamento()`**

- **Entrada:** nenhuma; lê configurações via `Env`.
- **Funcionamento:** valida configurações; cria `LimiteDao(new MySqlProvider())` e `Jira`; abre um `S3Client` no `try-with-resources`; lista capturas; ignora lotes com `.ok`; chama `processarJson()`; cria `.ok` em caso de sucesso; contabiliza arquivos e leituras.
- **Exemplo:** com três chaves no S3, duas já marcadas e uma inédita, somente a inédita é enviada ao parser. Os dois `.ok` anteriores permanecem intactos.
- **Falhas:** captura `S3Exception` e `IOException` por lote; configurações ausentes geram `IllegalStateException` capturada externamente. O método **não devolve** um indicador agregado de sucesso ou falha; imprime informações no console.

**`private void validarConfiguracao()`**

- **Entrada/saída:** nenhuma; lança `IllegalStateException` quando falta uma variável obrigatória.
- **Variáveis verificadas:** `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `S3_BUCKET`, `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN` e `JIRA_PROJECT_KEY`.
- **Exemplo:** ausência de `JIRA_PROJECT_KEY` interrompe a execução antes da listagem do S3.
- **Nota:** `S3_KEY` e `S3_PREFIX` não são obrigatórias neste método.

**`private static Jira criarJira()`**

- **Entrada:** lê `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN`.
- **Saída:** instância de `Jira` autenticada por cabeçalho Basic.
- **Exemplo:** remove barras `/` finais da URL antes de construir o cliente, evitando `//rest/api/3/...`.

**`private static int processarJson(S3Client s3, String bucket, String chave, LimiteDao dao, Jira jira, Set<String> jaAlertados)`**

- **Entrada:** cliente S3, bucket, chave do objeto, DAO, cliente Jira e um conjunto `jaAlertados`.
- **Saída:** número de leituras válidas percorridas nesse arquivo (`int`).
- **Funcionamento:** usa `GetObjectRequest`; abre `InputStream` + `JsonFactory`/`JsonParser`; exige objeto na raiz; extrai `endereco_mac` e `lote_id`; encaminha os arrays ao `ProcessadorJson`; após fechar o fluxo, imprime total e agrupamentos, chama `ChamadoService.criarTicket()` para cada ocorrência.
- **Exemplo:** 81 leituras numéricas válidas podem resultar em apenas 1 ocorrência agrupada: são medidas diferentes ou capturas sucessivas, mas só o grupo que atingiu limite vira ticket.
- **Detalhe:** `jaAlertados` está presente na assinatura e é criado no ciclo, mas **não é utilizado** no corpo desse método na versão analisada. A deduplicação efetiva ocorre pelo mapa de ocorrências, etiquetas do Jira e `.ok` do S3.
- **Falhas:** IO de leitura, erro SQL encapsulado como IO e erro HTTP encapsulado como IO podem impedir o `.ok` correspondente.

### 5.3 `ProcessadorJson` — leitura recursiva, classificação e agrupamento

**Arquivo:** [`ProcessadorJson.java`](../src/main/java/school/sptech/service/ProcessadorJson.java)  
**Papel:** percorrer a estrutura do JSON e transformar leituras de métricas em ocorrências classificadas.

**`public static int processarRegistros(JsonParser parser, String tipo, String mac, LimiteDao dao, Map cacheLimites, Set metricasConsultadas, Map alertasDoLote)`**

- **Entrada:** parser posicionado em `START_ARRAY`; tipo (`metricas`, `processos`, `containers`); MAC; DAO; coleções do lote.
- **Saída:** contador de leituras válidas.
- **Funcionamento:** inicia `percorrerArray(parser, tipo, null, ...)`.
- **Exemplo:** ao receber `tipo = "metricas"`, inicia o percurso com caminhos como `metricas[0]`.

**`private static int percorrerArray(...)`**

- **Entrada:** caminho atual, recurso herdado e contexto do processamento.
- **Saída:** soma de leituras válidas nos itens visitados.
- **Funcionamento:** percorre até `END_ARRAY`, incrementa `indice`, chama `percorrerObjeto()` nos objetos e a si próprio nos arrays aninhados.
- **Exemplo:** um array com dois objetos produz os caminhos `metricas[0]` e `metricas[1]`. Elementos escalares do array não são comparados como métricas.

**`private static int percorrerObjeto(...)`**

- **Entrada:** objeto JSON atual, caminho, recurso herdado, MAC, DAO e mapas do lote.
- **Saída:** número de leituras válidas encontradas nesse objeto e nos descendentes.
- **Funcionamento:** lê campos `metrica`, `campo_origem`, `status`, `valor`, `indice`, `nome`, `dispositivo`, `montagem` e `pid`; percorre objetos e arrays filhos; ao terminar o objeto, verifica `status`, `metrica` e valor numérico finito; se válidos, chama `avaliarMetrica()`.
- **Exemplo:** `{ "metrica": "ram_percent", "valor": 93, "status": "ok" }` é elegível à avaliação; `{ "valor": "93" }` não é, porque o valor é string e não um token numérico.
- **Nota:** a leitura é recursiva; não é uma conversão integral do JSON para um POJO.

**`private static String identificarRecurso(...)`**

- **Entrada:** caminho JSON, recurso herdado e identificadores (`indice`, `nome`, `dispositivo`, `montagem`, `pid`).
- **Saída:** descrição textual do recurso.
- **Regras e exemplos:**

| Caminho / dados | Recurso produzido |
|---|---|
| `metricas[0]` | `Máquina` |
| `.logicas.leituras[` com `indice = 1` | `CPU lógica 1` |
| `.disco.volumes[` com `dispositivo = /dev/sda1`, `montagem = /` | `Volume /dev/sda1 (/)` |
| `.disco.io[` com `dispositivo = sda` | `Disco sda` |
| `.rede.interfaces[` com `nome = eth0` | `Interface eth0` |
| `processos[0]` com `nome = java`, `pid = 123` | `Processo java (PID 123)` |
| `containers[0]` com `nome = mysql` | `Contêiner mysql` |
| Sem identificador próprio | Recurso herdado; se ausente, o próprio caminho |

**`private static void avaliarMetrica(...)`**

- **Entrada:** MAC, nome da métrica, campo de origem, valor, recurso, DAO e caches do lote.
- **Saída:** nenhuma diretamente; acrescenta ou substitui uma ocorrência no mapa quando um limite é atingido.
- **Funcionamento:** consulta o banco **uma vez por métrica por arquivo**, armazena limites no cache; se não houver limite, registra `[SEM LIMITE]`. Avalia o crítico da condição 1 e da condição 2 **antes** das atenções. Cria `Main.Ocorrencia` com operador e limite **efetivamente disparados**.
- **Exemplo:** para `cpu_percent_percpu = 95`, com crítico `> 90` e atenção `> 70`, classifica como **CRITICO** (não ATENCAO), registrando `operador = ">"`, `limite = 90`.
- **Segunda condição:** para um exemplo didático com crítico `valor < 10`, uma leitura de `7` também seria crítica, mesmo se a primeira condição fosse `valor > 90`.

**`private static boolean atingiuLimite(double valor, Double limite, String operador)`**

- **Entrada:** valor observado, limite opcional e operador configurado.
- **Saída:** `true` se a comparação é satisfeita, `false` se não é.
- **Operadores:** `>`, `>=`, `<`, `<=`, `=`/`==` e `!=`/`<>`; operador nulo/vazio vira `>`; limite nulo retorna `false`; operador desconhecido registra aviso e retorna `false`.
- **Exemplos:** `atingiuLimite(80, 70, ">") = true`, `atingiuLimite(70, 70, ">") = false`, `atingiuLimite(70, 70, ">=") = true`.

**`private static void registrarOcorrencia(Map<String, Ocorrencia> ocorrencias, Ocorrencia nova)`**

- **Entrada:** mapa de ocorrências do lote e nova ocorrência.
- **Saída:** atualização do mapa; nenhuma resposta direta.
- **Chave:** `mac|recurso|metrica|campoOrigem`, preservando diferenças entre recursos da mesma máquina.
- **Exemplo:** `CPU lógica 1` e `CPU lógica 2` não se misturam. Se a CPU lógica 1 teve atenção e depois crítico no **mesmo lote**, a ocorrência retida é crítica; se duas leituras são igualmente críticas, permanece a última percorrida.

### 5.4 `LoteS3Service` — descoberta e marcador dos lotes

**Arquivo:** [`LoteS3Service.java`](../src/main/java/school/sptech/s3/LoteS3Service.java)  
**Papel:** usar o mesmo `S3Client` e bucket para localizar capturas e controlar o processamento persistente.

**`public LoteS3Service(S3Client s3, String bucket)`**

Armazena as dependências da instância. Exemplo: `new LoteS3Service(s3Client, "bucket-exemplo")`.

**`public List<String> obterChavesCaptura()`**

- **Entrada:** variáveis opcionais `S3_KEY` e `S3_PREFIX`.
- **Saída:** lista de chaves S3.
- **Funcionamento:** com `S3_KEY` não vazia, retorna somente essa chave; caso contrário, lista páginas com `listObjectsV2Paginator`, seleciona nomes terminados em `/capturas.json` e ordena alfabeticamente.
- **Exemplo:** `S3_PREFIX=client/lotes/` pode localizar `client/lotes/maquina=.../lote=.../capturas.json`; não retorna outros arquivos JSON do prefixo.

**`public boolean loteJaProcessado(String chave)`**

- **Entrada:** chave da captura.
- **Saída:** `true` se a chave do marcador `.ok` existe, `false` se o S3 respondeu 404.
- **Funcionamento:** monta o caminho com `chaveMarcador()` e chama `s3.headObject()`.
- **Exemplo:** um `.ok` previamente criado faz a próxima execução ignorar o mesmo arquivo sem baixá-lo novamente.
- **Importante:** erros 403 e 5xx não são interpretados como “não processado”; a exceção é propagada.

**`public void marcarLoteProcessado(String chave)`**

- **Entrada:** chave da captura concluída.
- **Saída:** objeto de texto no S3.
- **Funcionamento:** usa `putObject()` com `STATUS=PROCESSADO`, `ORIGEM` e `DATA` (`Instant.now()`).
- **Exemplo de conteúdo do marcador:**

```text
STATUS=PROCESSADO
ORIGEM=client/lotes/maquina=servidor-01/lote=lote-001/capturas.json
DATA=2026-11-05T20:30:00Z
```

A data do exemplo é ilustrativa; o código usa o instante real da escrita.

**`private static String chaveMarcador(String chave)`**

- **Entrada:** chave original que deve começar com `S3_PREFIX` e terminar em `/capturas.json`.
- **Saída:** caminho equivalente sob `S3_PROCESSADOS_PREFIX`, com sufixo `.ok`.
- **Exemplo:**

```text
S3_PREFIX=client/lotes/
S3_PROCESSADOS_PREFIX=client/controle/processados/

Entrada: client/lotes/maquina=01/lote=abc/capturas.json
Saída:   client/controle/processados/maquina=01/lote=abc/capturas.json.ok
```

- **Falha:** se `S3_KEY` apontar para um arquivo fora de `S3_PREFIX`, o processamento pode concluir, mas a criação do marcador lança `IllegalArgumentException`. É necessário manter os caminhos coerentes.

### 5.5 `LimiteDao` — consulta JDBC ao MySQL

**Arquivo:** [`LimiteDao.java`](../src/main/java/school/sptech/docker/LimiteDao.java)  
**Papel:** localizar a configuração de limites relacionada ao perfil do servidor.

**`public record Limite(...)`**

Representa a configuração retornada pelo SQL: `nomeColuna`, `nome`, `unidade`, `atencao`, `critico`, `operador`, `operador2`, `atencao2` e `critico2`. A segunda condição pode estar ausente (`null`).

**`public LimiteDao(MySqlProvider provider)`**

Recebe o objeto que abre conexões JDBC, sem criar internamente as configurações do banco.

**`public Map<String, Limite> buscarPorMac(String mac, Collection<String> nomesMetricas)`**

- **Entrada:** MAC do servidor e métricas identificadas no JSON.
- **Saída:** mapa de configurações por identificador de coluna em minúsculas.
- **Funcionamento:** normaliza nomes (`trim`, minúsculas, `distinct`), monta placeholders `?` para o `IN`, executa `SELECT DISTINCT` com `PreparedStatement` nas tabelas `servidores`, `componentes_perfil` e `componentes`, filtrando configurações que possuem ao menos um limite preenchido.
- **Exemplo:** `buscarPorMac("02:00:00:00:00:01", List.of("cpu_percent", "ram_percent"))` só retorna as métricas habilitadas no perfil daquele servidor com limite configurado; a existência da métrica no JSON, isoladamente, não basta.
- **Segurança:** a consulta usa parâmetros em vez de concatenar valores do MAC e dos nomes das métricas diretamente no SQL.
- **Falhas:** problemas de conexão/SQL lançam `SQLException`, encapsulada como `IOException` pelo `ProcessadorJson`.

**`private static Double paraDouble(BigDecimal valor)`**

Converte o decimal do `ResultSet` para `Double`, mantendo `null` quando o limite não foi configurado. Exemplo: `BigDecimal("90.00") → 90.0`.

### 5.6 `MySqlProvider` — conexão ao banco

**Arquivo:** [`MySqlProvider.java`](../src/main/java/school/sptech/client/MySqlProvider.java)

**`public MySqlProvider()`** constrói a URL JDBC a partir de `DB_HOST`, `DB_PORT` (padrão `3306`), `DB_NAME`, `DB_USER` e `DB_PASSWORD`. O código atual acrescenta `useSSL=false`, `allowPublicKeyRetrieval=true` e `serverTimezone=UTC` à URL.

**`public Connection getConnection() throws SQLException`** usa `DriverManager.getConnection(url, usuario, senha)` e devolve uma conexão ao DAO. **Exemplo:** quando `DB_HOST=mysql` dentro de uma rede Docker, o nome `mysql` é resolvido no contexto da rede do contêiner; executar Java fora dessa rede exige um endereço/porta acessíveis a partir do host.

### 5.7 `S3Provider` — cliente AWS

**Arquivo:** [`S3Provider.java`](../src/main/java/school/sptech/client/S3Provider.java)

**`public S3Provider()`** cria `DefaultCredentialsProvider`, permitindo usar a cadeia padrão de credenciais da AWS, incluindo IAM Role de EC2 quando configurada.

**`public S3Client getS3Client()`** lê `AWS_REGION` (padrão `us-east-1`) e devolve um cliente regionalizado com as credenciais configuradas. **Exemplo:** na EC2, uma IAM Role pode dar acesso ao bucket sem chaves fixas no `.env`. O consumidor deve fechar o cliente, como já acontece no `try-with-resources` do `ProcessamentoService`.

### 5.8 `Env` — leitura e validação de configuração

**Arquivo:** [`Env.java`](../src/main/java/school/sptech/config/Env.java)

**Inicialização estática `DOTENV`:** carrega o `.env` do diretório `System.getProperty("user.dir")`, tolerando arquivo ausente. As variáveis do sistema podem prevalecer, conforme o comportamento do `dotenv-java` usado na aplicação.

**`private Env()`** impede a instanciação da classe utilitária.

**`public static String get(String nome, String valorPadrao)`** retorna uma variável não vazia sem espaços nas pontas ou o padrão fornecido. **Exemplo:** `Env.get("DB_PORT", "3306")` devolve `3306` quando a variável está ausente.

**`public static String require(String nome)`** devolve uma configuração obrigatória ou lança `IllegalStateException`. **Exemplo:** `Env.require("S3_BUCKET")` interrompe o ciclo com mensagem explicativa se o nome do bucket não tiver sido configurado.

### 5.9 `ChamadoService` — regras de negócio do Jira

**Arquivo:** [`ChamadoService.java`](../src/main/java/school/sptech/service/ChamadoService.java)

**`public static void criarTicket(Jira jira, Main.Ocorrencia alerta) throws IOException`**

- **Entrada:** cliente Jira e ocorrência classificada.
- **Funcionamento:** gera etiqueta, monta título, limita o título a 255 caracteres, busca uma issue não concluída com a mesma etiqueta; se já existe e a ocorrência é `CRITICO`, chama `atualizarChamadoParaCritico()`; se já existe e é `ATENCAO`, não altera a issue; se não existe, chama `createIssue()`.
- **Exemplo:** CPU lógica 1 a 80% cria um chamado de atenção. Um lote posterior da mesma CPU a 95% encontra o chamado pela etiqueta e o promove para crítico **sem criar outra issue**.
- **Importante:** somente a **primeira** chegada ao nível crítico altera título/etiqueta. Se o título já começar com `[CRÍTICO]` e a etiqueta crítica já existir, novos valores críticos **não atualizam o número do título**.
- **Interrupção:** preserva o estado da thread e encapsula `InterruptedException` como `IOException` na criação.

**`private static String montarTitulo(Main.Ocorrencia alerta)`**

- **Entrada:** gravidade, recurso, nome da métrica, valor, operador, limite, MAC.
- **Saída:** título legível, formatado com vírgula decimal no padrão brasileiro.
- **Exemplo:**

```text
[ATENÇÃO] Uso elevado de CPU lógica 1 — 80,0% (limite > 70,0%) | Servidor 02:00:00:00:00:01
```

- **Regras:** conhece nomes `cpu_percent`, `cpu_percent_percpu` e `ram_percent`; para operador iniciado por `<`, usa descrição de “valor baixo”; aplica `%` apenas a alguns nomes específicos, como `disk_percent`. Outras métricas aparecem com seu nome técnico e sem unidade adicionada.

**`private static String gerarEtiqueta(Main.Ocorrencia alerta)`**

- **Entrada:** MAC, recurso, métrica e `campoOrigem` (valor vazio quando nulo).
- **Saída:** `coretrace-` seguido dos **24 primeiros caracteres hexadecimais** do SHA-256 do identificador em minúsculas.
- **Exemplo:** CPU lógica 1 com 80% ou 95%, no mesmo MAC e campo de origem, gera **a mesma etiqueta**, porque o valor e a gravidade não fazem parte da identificação. CPU lógica 2 gera uma etiqueta diferente.

### 5.10 `Jira` — cliente REST do Jira Cloud

**Arquivo:** [`Jira.java`](../src/main/java/school/sptech/config/Jira.java)

**`public Jira(String baseUrl, String email, String apiToken)`**

Prepara autenticação HTTP Basic com e-mail + token codificados em Base64, `HttpClient` com timeout de conexão de 30 segundos e `ObjectMapper` do Jackson. Base64 **não é criptografia**: a URL deve ser HTTPS, e o token não deve aparecer em logs.

**`public String createIssue(String projectKey, String summary, String issueType)`**

Sobrecarga de compatibilidade que delega a `createIssue(projectKey, summary, issueType, null)`. **Exemplo:** criação de issue sem etiqueta.

**`public String createIssue(String projectKey, String summary, String issueType, String etiqueta)`**

- **Entrada:** chave do projeto, título, nome do tipo (padrão configurado pelo chamador) e etiqueta opcional.
- **Funcionamento:** monta os DTOs (`ProjectDto`, `IssueTypeDto`, `IssueFieldsDto`, `IssueRequestDto`), converte-os para `ObjectNode`, acrescenta `fields.labels` quando necessário e envia `POST /rest/api/3/issue`.
- **Saída:** corpo da resposta HTTP quando o status for 2xx (uma string JSON, não apenas a chave da issue).
- **Falhas:** status não 2xx gera `IOException` com código HTTP e corpo devolvido pelo Jira.
- **Exemplo de corpo enviado (dados ilustrativos):**

```json
{
  "fields": {
    "project": { "key": "CT" },
    "summary": "[ATENÇÃO] Uso elevado de CPU lógica 1 — 80,0% (limite > 70,0%) | Servidor 02:00:00:00:00:01",
    "issuetype": { "name": "Task" },
    "labels": ["coretrace-<hash-do-problema>"]
  }
}
```

**`public String buscarChamadoAberto(String projectKey, String etiqueta) throws IOException`**

- **Entrada:** chave do projeto e etiqueta estável.
- **Funcionamento:** faz `POST /rest/api/3/search/jql` com `maxResults=1` e uma consulta JQL equivalente a:

```sql
project = "CT" AND labels = "coretrace-<hash-do-problema>"
AND statusCategory != Done
```

- **Saída:** chave da primeira issue (`"CT-15"`) ou `null` quando `issues` é uma lista vazia.
- **Falhas:** resposta HTTP diferente de 200, JSON inválido ou ausência de estrutura esperada geram `IOException`; um erro **não é confundido com “nenhum chamado”**.
- **Observação:** a busca depende da indexação do Jira; resultados recém-criados podem não aparecer imediatamente.

**`public boolean atualizarChamadoParaCritico(String chaveChamado, String novoTitulo) throws IOException`**

- **Entrada:** chave da issue existente e título crítico proposto.
- **Funcionamento:** `GET /rest/api/3/issue/{chave}?fields=summary,labels`; verifica se o título começa com `[CRÍTICO]`/`[CRITICO]` e se a etiqueta `coretrace-critico` existe. Se faltar algo, envia `PUT` com `fields.summary` e/ou operação de adição em `update.labels`.
- **Saída:** `true` quando enviou uma atualização bem-sucedida; `false` quando a issue já estava classificada como crítica tanto no título quanto na etiqueta.
- **Exemplo:** `CT-15`, que era atenção, torna-se crítico sem perder a etiqueta hash do problema.
- **Limite da implementação:** não altera o campo nativo **Priority** nem a transição de **status**; o encerramento permanece manual.

### 5.11 `school.sptech.model.Ocorrencia` — record de modelo

**Arquivo:** [`model/Ocorrencia.java`](../src/main/java/school/sptech/model/Ocorrencia.java)

**`public record Ocorrencia(...)`** contém os mesmos oito campos de `Main.Ocorrencia` e seus acessores gerados automaticamente, como `mac()`, `valor()` e `nivel()`. **Exemplo de uso direto:** `new school.sptech.model.Ocorrencia(...).nivel()` devolveria o nível fornecido.

**Estado no ZIP:** **não é utilizado** pelos serviços examinados, que importam `Main.Ocorrencia`. Essa distinção é importante ao navegar pelo projeto ou planejar uma unificação futura dos modelos.

### 5.12 `ProjectDto` — projeto da issue

**Arquivo:** [`ProjectDto.java`](../src/main/java/school/sptech/dto/ProjectDto.java)

- **`ProjectDto()`**: construtor vazio, útil para serialização/desserialização.
- **`ProjectDto(String key)`**: cria o objeto com a chave do projeto; exemplo `new ProjectDto("CT")`.
- **`getKey()`**: devolve a chave atual, como `"CT"`.
- **`setKey(String key)`**: modifica a chave.
- **Forma JSON:** `{ "key": "CT" }`.

### 5.13 `IssueTypeDto` — tipo da issue

**Arquivo:** [`IssueTypeDto.java`](../src/main/java/school/sptech/dto/IssueTypeDto.java)

- **`IssueTypeDto()`**: construtor vazio.
- **`IssueTypeDto(String name)`**: cria um tipo como `"Task"`.
- **`getName()`**: devolve o nome do tipo.
- **`setName(String name)`**: altera o nome.
- **Forma JSON:** `{ "name": "Task" }`.

### 5.14 `IssueFieldsDto` — campos da issue

**Arquivo:** [`IssueFieldsDto.java`](../src/main/java/school/sptech/dto/IssueFieldsDto.java)

- **`IssueFieldsDto()`**: construtor vazio.
- **`IssueFieldsDto(ProjectDto project, String summary, IssueTypeDto issuetype)`**: reúne os três campos básicos usados na criação.
- **`getProject()` / `setProject(ProjectDto)`**: lê/altera o projeto.
- **`getSummary()` / `setSummary(String)`**: lê/altera o título.
- **`getIssuetype()` / `setIssuetype(IssueTypeDto)`**: lê/altera o tipo.
- **Exemplo:** `new IssueFieldsDto(new ProjectDto("CT"), "CPU em atenção", new IssueTypeDto("Task"))`.

### 5.15 `IssueRequestDto` — corpo da requisição

**Arquivo:** [`IssueRequestDto.java`](../src/main/java/school/sptech/dto/IssueRequestDto.java)

- **`IssueRequestDto()`**: construtor vazio.
- **`IssueRequestDto(IssueFieldsDto fields)`**: encapsula os campos sob a propriedade `fields`.
- **`getFields()` / `setFields(IssueFieldsDto)`**: lê/altera os campos.
- **Exemplo:** `new IssueRequestDto(fields)` produz, após serialização pelo Jackson, a estrutura `{ "fields": { ... } }`.

## 6. Cenários práticos completos

### Cenário A — primeira captura acima do limite de atenção

**Configuração didática no MySQL:** servidor com MAC `02:00:00:00:00:01`, métrica `cpu_percent_percpu` habilitada, `operador = ">"`, `limite_atencao = 70` e `limite_critico = 90`.

1. Um arquivo JSON novo informa `CPU lógica 1 = 80` com `status = "ok"`.
2. O `ProcessadorJson` contabiliza a leitura, busca o limite no `LimiteDao` e produz `ATENCAO`, operador `>`, limite `70`.
3. `registrarOcorrencia()` adiciona a chave `MAC|CPU lógica 1|cpu_percent_percpu|cpu_percent_percpu`.
4. `ChamadoService` gera um título legível e uma etiqueta baseada em SHA-256.
5. Sem issue não concluída com essa etiqueta, `Jira.createIssue()` cria um chamado de atenção.
6. Ao finalizar o arquivo, `LoteS3Service` grava o `.ok`.

**Resultado esperado:** um chamado de atenção e um marcador `.ok` para o arquivo.

### Cenário B — mesmo problema evolui para crítico em outro lote

1. Cinco minutos depois, chega **outro** `capturas.json` com a mesma máquina/recurso/métrica, agora `95`.
2. O segundo lote não possui `.ok`, então é avaliado normalmente.
3. O crítico `95 > 90` prevalece sobre a atenção `95 > 70`.
4. A etiqueta da ocorrência permanece igual, pois `gerarEtiqueta()` não usa `valor`, `nivel` ou `lote_id`.
5. O Jira encontra a issue de atenção e `atualizarChamadoParaCritico()` altera o título e adiciona `coretrace-critico`.
6. O novo lote recebe seu próprio `.ok`.

**Resultado esperado:** **a mesma issue** passa a indicar crítico. Não é criada uma segunda issue quando a busca encontra corretamente a primeira.

### Cenário C — mesma captura processada outra vez

A aplicação reinicia e encontra um arquivo já marcado:

```text
[IGNORADO] Arquivo já processado: client/lotes/.../capturas.json
```

`loteJaProcessado()` confirma o `.ok` com `headObject()`, o arquivo não é baixado e nenhum novo chamado é gerado por essa execução.

### Cenário D — problema normaliza, mas o chamado continua aberto

Depois de um crítico, a CPU cai para 50%, sem atingir nenhum limite. `avaliarMetrica()` não cria uma ocorrência, de modo que o Jira **não recebe pedido de edição ou encerramento**. A equipe continua responsável pela conclusão manual do ticket.

### Cenário E — limite inferior e superior na mesma métrica

**Configuração didática:** condição 1 `> 90` crítico e `> 70` atenção; condição 2 `< 10` crítico e `< 20` atenção.

| Leitura | Comparação | Resultado |
|---|---|---|
| `95` | `95 > 90` | `CRITICO` (condição 1) |
| `80` | `80 > 70` | `ATENCAO` (condição 1) |
| `7` | `7 < 10` | `CRITICO` (condição 2) |
| `15` | `15 < 20` | `ATENCAO` (condição 2) |
| `50` | Nenhuma condição | Sem ocorrência |

Essa tabela demonstra a lógica de comparação, não pressupõe que tais limites já estejam cadastrados no banco do projeto.

### Cenário F — falha do Jira e reprocessamento posterior

Se o Jira responder com erro durante `createIssue()`, a exceção chega a `ProcessamentoService` como `IOException`; aquele lote **não recebe** seu `.ok`. No ciclo seguinte, poderá ser tentado novamente. Se um ticket anterior tiver sido criado parcialmente, a consulta por etiqueta pode encontrá-lo e evitar outra abertura, **mas não existe garantia transacional de exatamente uma issue**.

## 7. Configuração e execução

### Dependências e build

O `pom.xml` da versão analisada utiliza **Java 21**, Maven, Jackson Databind, AWS SDK S3, dotenv-java, MySQL Connector/J e Maven Shade Plugin. O projeto configura o manifesto para `school.sptech.Main` e gera o JAR empacotado **`target/coretrace.jar`**.

```bash
# Na raiz do repositório, com JDK 21 e Maven disponíveis
mvn clean package
java -jar target/coretrace.jar
```

**Comportamento esperado:** depois de iniciar, o processo permanece ativo e executa ciclos periódicos. Para interrompê-lo em primeiro plano, use `Ctrl+C`.

### Variáveis de ambiente

Este é um **modelo sem credenciais reais**. Não publique o `.env` do ZIP ou tokens e senhas em issues, commits e documentação.

```dotenv
# Banco de dados
DB_HOST=localhost
DB_PORT=3306
DB_NAME=nome_do_banco
DB_USER=usuario_com_permissao_de_leitura
DB_PASSWORD=SUBSTITUIR_LOCALMENTE

# Armazenamento
AWS_REGION=us-east-1
S3_BUCKET=nome_do_bucket
S3_PREFIX=client/lotes/
S3_PROCESSADOS_PREFIX=client/controle/processados/
# S3_KEY=client/lotes/maquina=.../lote=.../capturas.json

# Jira
JIRA_BASE_URL=https://sua-organizacao.atlassian.net
JIRA_EMAIL=usuario@exemplo.com
JIRA_API_TOKEN=SUBSTITUIR_LOCALMENTE
JIRA_PROJECT_KEY=CT
JIRA_ISSUE_TYPE=Task
```

| Variável | Obrigatória em `validarConfiguracao()`? | Padrão / uso |
|---|---|---|
| `DB_HOST` | Sim | Host do MySQL |
| `DB_PORT` | Não | `3306` |
| `DB_NAME` | Sim | Banco/schema |
| `DB_USER` / `DB_PASSWORD` | Sim | Credenciais JDBC |
| `AWS_REGION` | Não | `us-east-1` |
| `S3_BUCKET` | Sim | Nome do bucket |
| `S3_PREFIX` | Não | `client/lotes`/`client/lotes/` (normalizados) |
| `S3_KEY` | Não | Quando presente, seleciona uma chave única |
| `S3_PROCESSADOS_PREFIX` | Não | **No código, o padrão ainda é `client/controle/testes-processados/`** |
| `JIRA_BASE_URL` | Sim | URL base da instância Jira |
| `JIRA_EMAIL` / `JIRA_API_TOKEN` | Sim | HTTP Basic |
| `JIRA_PROJECT_KEY` | Sim | Chave do projeto |
| `JIRA_ISSUE_TYPE` | Não | `Task` |

**Observação:** no `.env` de produção, configure `S3_PROCESSADOS_PREFIX` explicitamente para não usar acidentalmente o prefixo padrão de testes. O exemplo acima não contém segredos utilizáveis.

### Permissões de infraestrutura

- **S3:** listar o bucket/prefixo, ler capturas e metadados, e gravar marcadores `.ok` (`ListBucket`, `GetObject` e `PutObject` nos recursos apropriados). As políticas exatas dependem de como os prefixos estão organizados.
- **MySQL:** executar `SELECT` nas tabelas `servidores`, `componentes_perfil`, `componentes` e acessar o banco a partir do ambiente do Java.
- **Jira:** credenciais válidas e permissões para procurar, criar e editar issues do projeto. Para escalonar é necessário que os campos de título e etiquetas estejam disponíveis para edição.

## 8. Operação, logs e resolução de problemas

| Log ou sintoma | Onde observar | Interpretação e ação sugerida |
|---|---|---|
| `Nenhum JSON encontrado no S3.` | `ProcessamentoService` | Confira `S3_BUCKET`, `S3_PREFIX` ou `S3_KEY` e o sufixo `/capturas.json`. |
| `[IGNORADO] Arquivo já processado` | `LoteS3Service` + orquestração | Existe `.ok` para a chave; isso é comportamento esperado. |
| `MAC não identificado` | `processarJson()` | `endereco_mac` não foi lido antes do array; confira o JSON de origem. |
| `Esperado array: metricas` | `processarJson()` | O campo existe, mas não contém array. |
| `[SEM LIMITE]` | `ProcessadorJson` | Métrica válida sem limite retornado para o MAC/perfil. |
| `[COMPARANDO]` | `ProcessadorJson` | O programa encontrou configuração e está avaliando os operadores. |
| `[JIRA] Chamado criado` | `ChamadoService` | O POST teve resposta de sucesso. |
| `[JIRA] Chamado ... já existe` | `ChamadoService` | Busca por etiqueta encontrou issue não concluída. |
| `[JIRA] Chamado ... atualizado para CRITICO` | `ChamadoService` | O PUT foi aceito; mesma issue escalada. |
| `HTTP 400` com erro no projeto | `Jira` | Confira `JIRA_PROJECT_KEY` e permissões da conta. |
| `[ERRO PROCESSAMENTO]` | `ProcessamentoService` | Uma `IOException` impediu o `.ok` daquele arquivo. |
| `Chave de captura inválida` | `LoteS3Service` | `S3_KEY` e `S3_PREFIX` não são compatíveis com a regra de `chaveMarcador()`. |

**Comportamento de mensagens:** a camada de orquestração captura erros por arquivo e continua tentando outros lotes. Ao fim do ciclo, a mensagem “Processamento concluído” **não significa necessariamente que todos os arquivos foram processados sem erro**; observe as mensagens individuais.

## 9. Limitações conhecidas da versão documentada

Estas observações descrevem pontos concretos do código e **não indicam alterações feitas por esta documentação**.

1. **Dois tipos `Ocorrencia`:** existe `Main.Ocorrencia` e `model.Ocorrencia`, mas o fluxo utiliza o primeiro.
2. **Comentário legado sobre CSV:** `CONFIGURACAO.md` e alguns comentários mencionam CSV; a implementação Java atual lê objetos **JSON** (`capturas.json`).
3. **Ordenação de propriedades:** o MAC deve aparecer antes dos arrays; a identificação do recurso pai também pode depender da ordem dos campos aninhados.
4. **Validação de `lote_id`:** seu conteúdo é lido e exibido, mas a versão analisada não rejeita explicitamente um `lote_id` ausente ou vazio.
5. **Parâmetro `jaAlertados`:** é criado e passado a `processarJson()`, mas não participa da lógica atual.
6. **Título crítico estático:** após marcar a issue como crítica (título e etiqueta), leituras críticas posteriores não atualizam novamente o valor numérico exibido no título.
7. **Deduplicação não transacional:** não há travamento de lotes entre múltiplas instâncias nem garantia de exatamente um ticket em falhas intermediárias ou atraso de indexação JQL.
8. **Critério do chamado aberto:** `statusCategory != Done` significa “não concluído”, e não necessariamente apenas o status literal “Open”.
9. **Sem encerramento automático:** valores normais não rebaixam nem concluem chamados.
10. **Prioridade nativa Jira:** a escalada altera título e etiqueta; não altera o campo `priority` do Jira.
11. **Prefixo padrão de testes:** `S3_PROCESSADOS_PREFIX` aponta para `client/controle/testes-processados/` se não for definido explicitamente.
12. **Configuração JDBC atual:** o driver usa `useSSL=false`; para uso produtivo, a política de criptografia de transporte merece revisão separada.

## 10. Roteiro recomendado de validação

Este ZIP não forneceu uma suíte de testes automatizados da lógica descrita. Os itens abaixo são um roteiro de validação manual, não resultados de testes executados nesta documentação.

- [ ] Compilar com JDK 21 e Maven: `mvn clean package`.
- [ ] Testar uma captura JSON com métrica `status = "ok"` e valor numérico.
- [ ] Confirmar que `LimiteDao` retorna a configuração correta para o MAC.
- [ ] Confirmar atenção, crítico e nenhuma ocorrência com limites conhecidos.
- [ ] Confirmar prioridade de crítico no mesmo lote.
- [ ] Confirmar que CPUs lógicas diferentes não são agrupadas entre si.
- [ ] Confirmar que um lote concluído recebe `.ok` e é ignorado no próximo ciclo.
- [ ] Confirmar criação do primeiro chamado, busca do existente e escalada para crítico.
- [ ] Confirmar que uma leitura normal **não fecha** o chamado.
- [ ] Simular falha de Jira e confirmar que o lote sem conclusão **não recebe `.ok`**.
- [ ] Verificar políticas AWS e permissões Jira no ambiente de implantação.
- [ ] Garantir que `.env`, tokens e qualquer pasta `.git/` de cópia local não sejam incluídos em pacotes públicos de distribuição.

## 11. Glossário e caminhos de referência

| Termo | Significado no módulo Java |
|---|---|
| **Captura** | Objeto JSON do S3 com dados da máquina e arrays de métricas/processos/contêineres |
| **Lote** | Unidade de processamento identificada por sua chave S3 e, dentro do JSON, pelo `lote_id` |
| **Métrica** | Nome usado em `metrica` no JSON e comparado a `componentes.nome_coluna` |
| **Recurso** | Identificador da parte monitorada: máquina, CPU lógica, disco, interface, processo ou contêiner |
| **Limite** | Configuração de atenção/crítico do perfil no MySQL, com até duas condições |
| **Ocorrência** | Resultado de comparação que atingiu atenção ou crítico; ainda não é um ticket |
| **Etiqueta estável** | Hash SHA-256 truncado que identifica o mesmo problema entre lotes |
| **Marcador `.ok`** | Objeto no S3 que indica que o arquivo de captura terminou seu processamento |
| **JQL** | Linguagem de consulta do Jira usada para localizar issues não concluídas |

---

**Como manter esta documentação:** revise este guia sempre que mudar o contrato JSON, a estrutura do banco, os operadores, a estratégia de deduplicação ou os endpoints da integração Jira. A documentação foi construída a partir do código efetivamente presente em `java.zip` e não modifica nenhuma classe Java.
