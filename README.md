# CoreTrace — integração Java de alertas

**Documentação técnica do módulo que transforma capturas JSON do Amazon S3 em ocorrências e chamados do Jira, a partir dos limites configurados no MySQL.**

> Documentação baseada no código Java do projeto. O módulo não realiza captura de métricas: ele consome os arquivos produzidos pelo pipeline externo.

## Documentação

- **[Guia técnico completo — leitura direta no GitHub](docs/GUIA_TECNICO.md)**: arquitetura, fluxo de execução, estrutura JSON, **15 classes e seus métodos**, SQL, integração Jira, configuração, cenários comentados, limites conhecidos e testes.
- **[Versão HTML interativa](docs/index.html)**: mesma documentação com navegação lateral, índice de classes e busca de seções. Para visualizar como site, abra `docs/index.html` em um navegador ou publique a pasta `docs/` no GitHub Pages.

## Fluxo principal

```text
Capturas JSON no S3
        ↓
LoteS3Service  →  verifica marcadores .ok
        ↓
ProcessamentoService  →  abre InputStream / JsonParser
        ↓
ProcessadorJson  ↔  LimiteDao  ↔  MySQL
        ↓
Ocorrências agrupadas
        ↓
ChamadoService  ↔  Jira REST API
        ↓
LoteS3Service  →  grava .ok ao concluir
```

O processo é agendado a cada cinco minutos **depois de terminar a execução anterior**. Chamados existentes são buscados por uma etiqueta estável, e ocorrências críticas podem escalar o mesmo ticket. O fechamento dos chamados é manual.

## Execução

Requer **JDK 21**, **Maven**, acesso ao **S3**, **MySQL** e **Jira Cloud**. Configure as variáveis de ambiente conforme [o guia](docs/GUIA_TECNICO.md#7-configuração-e-execução).

```bash
mvn clean package
java -jar target/coretrace.jar
```

**Segurança:** nunca publique `.env` ou senhas/tokens. O repositório já possui configuração para ignorar `.env`; confirme que ele não está versionado. O ZIP original usado para elaborar esta documentação incluía um `.env` local, e **esse arquivo não foi incluído neste pacote de documentação**.

## Escopo e manutenção

O guia documenta a implementação contida no arquivo `java.zip` utilizado como referência. Ele aponta limitações observáveis — inclusive duas definições de `Ocorrencia` e comentários antigos relacionados a CSV — sem alterar o código-fonte.
