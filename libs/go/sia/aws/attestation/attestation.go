//
// Copyright The Athenz Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package attestation

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"strings"

	"github.com/AthenZ/athenz/libs/go/sia/aws/stssession"
	"github.com/aws/aws-sdk-go-v2/service/sts"
)

type AttestationData struct {
	Role       string `json:"role,omitempty"`       //the IAM role. This must match the athenz service identity
	CommonName string `json:"commonName,omitempty"` //The common name for CSR. Different from Role if we're using service name only
	Access     string `json:"access,omitempty"`     //the temp creds access key id
	Secret     string `json:"secret,omitempty"`     //the temp creds secret key
	Token      string `json:"token,omitempty"`      //the temp creds session token
	Document   string `json:"document,omitempty"`   //for EC2 instance document
	Signature  string `json:"signature,omitempty"`  //for EC2 instance document pkcs7 signature
}

// New creates a new AttestationData with values fed to it and from the result of STS Assume Role.
// This requires an identity document along with its signature. The aws account and region will
// be extracted from the identity document.
func New(domain, service, region, account, ec2Document, ec2Signature string, useRegionalSTS, omitDomain bool) (string, error) {
	commonName := fmt.Sprintf("%s.%s", domain, service)
	var role string
	if omitDomain {
		role = service
	} else {
		role = commonName
	}
	tok, err := getSTSToken(useRegionalSTS, region, account, role)
	if err != nil {
		return "", err
	}
	data, err := json.Marshal(&AttestationData{
		Role:       role,
		CommonName: commonName,
		Document:   ec2Document,
		Signature:  ec2Signature,
		Access:     *tok.Credentials.AccessKeyId,
		Secret:     *tok.Credentials.SecretAccessKey,
		Token:      *tok.Credentials.SessionToken,
	})
	if err != nil {
		return "", err
	}

	return string(data), nil
}

func getSTSToken(useRegionalSTS bool, region, account, role string) (*sts.AssumeRoleOutput, error) {
	// Attempt STS AssumeRole
	stsClient, err := stssession.New(useRegionalSTS, region)
	if err != nil {
		log.Printf("unable to create new session: %v\n", err)
		return nil, err
	}
	roleArn := fmt.Sprintf("arn:aws:iam::%s:role/%s", account, role)
	log.Printf("Trying to assume role: %v\n", roleArn)
	return stsClient.AssumeRole(context.TODO(), &sts.AssumeRoleInput{
		RoleArn:         &roleArn,
		RoleSessionName: &role,
	})
}

func GetECSTaskId() string {
	ecs := os.Getenv("ECS_CONTAINER_METADATA_FILE")
	if ecs == "" {
		return ""
	}
	ecsMetaData, err := os.ReadFile(ecs)
	if err != nil {
		return ""
	}
	log.Printf("Content: %s\n", ecsMetaData)
	var docMap map[string]interface{}
	err = json.Unmarshal(ecsMetaData, &docMap)
	if err != nil {
		return ""
	}
	taskArn := docMap["TaskARN"].(string)
	arn := strings.Split(taskArn, ":")
	if len(arn) < 6 {
		return ""
	}
	taskComps := strings.Split(arn[5], "/")
	if taskComps[0] != "task" {
		return ""
	}
	var taskId string
	lenComps := len(taskComps)
	if lenComps == 2 || lenComps == 3 {
		taskId = taskComps[lenComps-1]
	} else {
		return ""
	}
	return taskId
}
