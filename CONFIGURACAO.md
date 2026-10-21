# CoreTrace — processamento dinâmico de vários CSVs

## Banco de dados: nenhuma alteração necessária

Esta versão do Java foi preparada para consultar a estrutura existente do MySQL. Ela **não executa `INSERT`, `UPDATE`, `DELETE`, `ALTER TABLE`, `DROP` ou scripts de migração**. O `LimiteDao` realiza somente uma consulta `SELECT` nas tabelas `servidores`, `componentes_perfil` e `componentes`.

O arquivo `database/script_banco_original.sql` é apenas uma cópia do script SQL originalmente enviado para referência; ele foi mantido sem alterações. Não é necessário executá-lo novamente nem executar qualquer outro SQL para usar o processamento dinâmico.

> Observação: o Java respeita as associações de componentes e limites que já estão gravadas no seu banco. Se os valores atuais de `fk_componente` apontarem para um componente diferente do pretendido, o programa não altera esses dados nem tenta adivinhar a intenção dos comentários do SQL; ele usará a configuração que realmente encontrar no banco.

## O que o Java faz

- Remove a lista fixa `METRICAS` com os nomes das colunas.
- Por padrão, lista os arquivos `.csv` do S3 dentro de `S3_PREFIX` e processa cada arquivo separadamente.
- Cada CSV pode ter um conjunto diferente de colunas.
- Cada linha precisa conter `endereco_mac`; esse MAC identifica o servidor e o perfil associado no MySQL.
- Os cabeçalhos são comparados dinamicamente com `componentes.nome_coluna`.
- Só são avaliadas métricas que existem no catálogo, estão associadas ao perfil do servidor e possuem pelo menos um limite configurado.
- Usa o operador já cadastrado em `componentes_perfil.operador` (`>`, `>=`, `<`, `<=`, `=`, `==`, `!=` ou `<>`).
- Continua criando tickets no Jira para ocorrências críticas, conforme a configuração da aplicação.

## Configurar `.env`

Na raiz do projeto:

```bash
cp .env.example .env
nano .env
chmod 600 .env
```

Preencha as credenciais reais. Não compartilhe o `.env`, não o inclua no Git e não coloque segredos no JAR.

Para processar todos os CSVs do prefixo:

```dotenv
S3_BUCKET=testejavacoretrace
S3_PREFIX=03-gold/
```

Remova ou comente qualquer linha `S3_KEY=...` já existente. Se `S3_KEY` estiver preenchido, o programa processa somente aquele arquivo. Se estiver ausente/vazio, ele lista todos os objetos `.csv` sob `S3_PREFIX`.

Cada CSV precisa conter `endereco_mac`. Os nomes das métricas devem corresponder aos valores existentes em `componentes.nome_coluna`, por exemplo `cpu_percent`, `ram_percent`, `disk_percent` ou `net_errors`. `timestamp` e `endereco_mac` são tratados como metadados, não como métricas.

## Permissões AWS para vários CSVs

A autenticação usa o provedor padrão de credenciais do SDK. Em uma EC2, prefira uma IAM Role com permissões mínimas:

- `s3:ListBucket` no bucket, restringindo o prefixo em `s3:prefix`;
- `s3:GetObject` nos objetos dentro do prefixo configurado.

Quando a IAM Role estiver configurada, não é necessário colocar `AWS_ACCESS_KEY_ID` ou `AWS_SECRET_ACCESS_KEY` no `.env` da EC2.

## MySQL

A aplicação precisa conseguir ler `servidores`, `componentes_perfil` e `componentes`. Recomenda-se usar um usuário com permissão de leitura somente nas tabelas necessárias. `DB_HOST=127.0.0.1` só é correto se o MySQL estiver acessível localmente a partir do processo Java.

A consulta usa o `endereco_mac` para localizar o servidor e obter o perfil relacionado. Métricas com os dois limites (`limite_atencao` e `limite_critico`) nulos não geram alerta.

## Compilar e executar

Com JDK 21 e Maven instalados, na raiz do projeto:

```bash
mvn clean package
java -jar target/coretrace.jar
```

Para executar na EC2, transfira o JAR recém-gerado e mantenha o `.env` no diretório de onde o Java será iniciado.

## Observações

- Cada CSV é processado sequencialmente, sem carregar todos os arquivos na memória de uma vez.
- O leitor trata aspas e vírgulas dentro de campos, mas considera que cada registro ocupa uma única linha física.
- Os valores devem ser numéricos para a comparação de limites; métricas categóricas, como `net_isup`, são ignoradas por essa validação numérica.
- A deduplicação de alertas vale apenas durante uma execução do programa, não entre execuções.
- O código não altera seu banco nem corrige automaticamente associações que já estejam incorretas.
