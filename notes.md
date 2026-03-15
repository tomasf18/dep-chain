- Gestao de pruing da arvore para valores conflituosos (quando locked, dar prune aos nao locked)
criar testes em que isto acontece (valores conflituosos)
- linha no verifyQC
- 2f+1 - fix code e report
- guaranteee that the f+1 received responses are actually identical (usar hash)

- clients precisam de assinar pedidos e replicas de verificar
- precissamos de sequence numbers no nivel da aplicacao para evitar replay attacks (a aplicacao guarda o estado dos clients: "O paulo tem 10 transacoes")