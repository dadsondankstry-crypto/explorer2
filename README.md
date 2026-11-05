# Explorador ZIP 3.7 — ZIPtoGit, auditoria e identidade visual

Projeto Android Java preparado para envio direto do ZIP ao ZiptoGit. Arquivos do projeto na raiz. **Não extraia e recompacte o ZIP.**

## Implementações da atualização 3.7

1. Novo ícone de aplicativo (pastas turquesa e fecho/zíper luminoso), com versões tradicionais e adaptativas para Android 8+.
2. Pesquisa por nome no conteúdo completo de um ZIP, **sem extrair** (sujeita ao limite anterior de entradas).
3. Exportação de inventário recursivo da pasta autorizada para CSV. Até 3.000 itens e 24 níveis. Inclui nomes ocultos quando a opção correspondente estiver ligada. Prevenção simples contra fórmulas de planilha.
4. Verificador estrutural básico de PWA: index.html, manifest, presença de Service Worker e chaves do manifesto. **Não é teste de execução do PWA.**
5. Aparência clara, escura ou automática conforme Android, e três tamanhos de texto para botões.
6. Revisão da auditoria item a item das 300 propostas, corrigindo também o status das ferramentas GZIP 3.6.
7. A interface mantém as funções anteriores e preserva o identificador com versão `3.7.0` (versionCode 19).

### Auditoria 300 (static code review)

- Código relacionado encontrado: **178**.
- Parcial / com limites: **48**.
- Não localizada implementação específica: **74**.
- Total: 300.

**NÃO são 178 funcionalidades comprovadamente completas.** Não afirmamos que todas as 300 foram construídas. São classificações de inspeção do projeto, sujeitas a revisão e testes de cada operação.

Consulte `AUDITORIA_300_3_7_OFFLINE.html` (abre no navegador mesmo offline) e `AUDITORIA_300_3_7.json`.

## Como compilar

1. Crie/selecione o repositório do Explorador ZIP no GitHub.
2. Envie o arquivo `ExploradorZIP_3_7_ZIPTOGIT_RAIZ.zip` diretamente pelo ZiptoGit.
3. Confirme `.github/workflows/main.yml` no GitHub.
4. Em **Actions**, aguarde a execução. Se estiver verde, baixe o artefato `ExploradorZIP-APK`.
5. Extraia o ZIP do artefato e instale o `app-debug.apk`. Assinatura de debug pode impedir atualização sem desinstalar versão anterior; faça backup das configurações primeiro.

## Segurança

- Primeiro use pastas e ZIPs de teste. Controle SAF não garante acesso à raiz do dispositivo.
- CSV e PWA são ferramentas de leitura (exceto o arquivo CSV gerado no destino escolhido).
- Enviar uma nova versão não é um teste de compilação: **esta versão não foi compilada com Android SDK nesta sessão**, nem testada em telefone.
- Persistem limitações das operações antigas (movimentação via provedor, datas, extração parcial, rede local sem HTTPS).
