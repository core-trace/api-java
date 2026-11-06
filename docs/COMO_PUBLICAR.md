# Como publicar esta documentação no GitHub

O pacote contém **apenas a documentação**. Ele não inclui os arquivos Java, o `.env` nem o histórico `.git` do projeto.

## Opção 1 — documentação navegável no próprio repositório

Copie para a **raiz do repositório `api-java`**:

```text
api-java/
├── README.md                       ← introdução e links
└── docs/
    ├── GUIA_TECNICO.md            ← guia completo para leitura no GitHub
    ├── COMO_PUBLICAR.md           ← estas instruções
    └── index.html                  ← versão visual
```

Se o repositório já tiver um `README.md` importante, **mescle o conteúdo do README fornecido** ao arquivo existente, em vez de sobrescrevê-lo sem revisar.

Depois, faça commit desses arquivos na branch desejada. O Markdown ficará acessível imediatamente pelo navegador do GitHub.

## Opção 2 — publicar o HTML usando GitHub Pages

1. Coloque o arquivo `index.html` na pasta `docs/` da branch escolhida.
2. No repositório, abra **Settings → Pages**.
3. Em **Build and deployment**, selecione **Deploy from a branch**, a branch desejada e a pasta **`/docs`** (quando essa opção estiver disponível).
4. Salve a configuração. O GitHub Pages mostrará o endereço publicado depois que a implantação estiver concluída.

O HTML é **autossuficiente**: não utiliza frameworks, fontes externas, CDNs nem acesso de rede para renderizar. A busca lateral filtra os títulos das seções e o botão de impressão abre a função de impressão do navegador.

## Como revisar alterações futuras

Atualize a documentação quando houver mudança no contrato `capturas.json`, nas condições de alerta, nos campos dos DTOs, no prefixo dos marcadores `.ok` ou nos endpoints da API Jira.

**Não publique** o `.env` que estava no ZIP enviado. O guia usa somente variáveis fictícias e placeholders.
